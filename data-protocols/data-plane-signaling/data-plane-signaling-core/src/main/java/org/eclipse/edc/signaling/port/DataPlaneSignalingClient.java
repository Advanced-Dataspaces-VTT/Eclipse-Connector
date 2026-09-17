/*
 *  Copyright (c) 2025 Think-it GmbH
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Think-it GmbH - initial API and implementation
 *
 */

package org.eclipse.edc.signaling.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.eclipse.edc.connector.controlplane.dataplane.spi.instance.DataPlaneInstance;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.signaling.domain.DataFlowPrepareMessage;
import org.eclipse.edc.signaling.domain.DataFlowResumeMessage;
import org.eclipse.edc.signaling.domain.DataFlowStartMessage;
import org.eclipse.edc.signaling.domain.DataFlowStartedNotificationMessage;
import org.eclipse.edc.signaling.domain.DataFlowStatusMessage;
import org.eclipse.edc.signaling.domain.DataFlowSuspendMessage;
import org.eclipse.edc.signaling.domain.DataFlowTerminateMessage;
import org.eclipse.edc.signaling.domain.DspDataAddress;
import org.eclipse.edc.signaling.spi.authorization.SignalingAuthorizationRegistry;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.spi.result.Result;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Collections.emptyMap;
import static org.eclipse.edc.spi.response.ResponseStatus.FATAL_ERROR;

/**
 * Client that implements the Data Plane Signaling spec
 */
public class DataPlaneSignalingClient {

    private static final MediaType TYPE_JSON = MediaType.parse("application/json");
    private static final String EDC_NAMESPACE = "https://w3id.org/edc/v0.0.1/ns/";
    private static final String JSON_LD_CONTEXT = "@context";
    private static final String JSON_LD_TYPE = "@type";

    private final DataPlaneInstance dataPlane;
    private final EdcHttpClient httpClient;
    private final Supplier<ObjectMapper> objectMapperSupplier;
    private final SignalingAuthorizationRegistry authorizationRegistry;

    public DataPlaneSignalingClient(DataPlaneInstance dataPlane, EdcHttpClient httpClient,
                                    Supplier<ObjectMapper> objectMapperSupplier,
                                    SignalingAuthorizationRegistry authorizationRegistry) {
        this.dataPlane = dataPlane;
        this.httpClient = httpClient;
        this.objectMapperSupplier = objectMapperSupplier;
        this.authorizationRegistry = authorizationRegistry;
    }

    public StatusResult<DataFlowStatusMessage> prepare(DataFlowPrepareMessage request) {
        return send("prepare", request, body -> dataFlowStatusMessage(body, true));
    }

    public StatusResult<DataFlowStatusMessage> start(DataFlowStartMessage request) {
        return send("start", request, body -> dataFlowStatusMessage(body, false));
    }

    public StatusResult<Void> suspend(String flowId, DataFlowSuspendMessage message) {
        return send(flowId + "/suspend", message, this::discardResponseBody);
    }

    public StatusResult<DataFlowStatusMessage> resume(String flowId, DataFlowResumeMessage message) {
        return send(flowId + "/resume", message, body -> dataFlowStatusMessage(body, false));
    }

    public StatusResult<Void> terminate(String flowId, DataFlowTerminateMessage message) {
        return send(flowId + "/terminate", message, this::discardResponseBody);
    }

    public StatusResult<Void> started(String flowId, DataFlowStartedNotificationMessage message) {
        return send(flowId + "/started", message, this::discardResponseBody);
    }

    public StatusResult<Void> completed(String flowId) {
        return send(flowId + "/completed", emptyMap(), this::discardResponseBody);
    }

    private <T> StatusResult<T> send(String path, Object message, Function<ResponseBody, Result<T>> extractBody) {
        return createRequestBuilder(message, dataPlane.getUrl() + "/" + path)
                .compose(builder -> {
                    var response = httpClient.execute(builder.build(), r -> handleResponse(r, extractBody));
                    if (response.succeeded()) {
                        return StatusResult.success(response.getContent());
                    } else {
                        return StatusResult.fatalError(response.getFailureDetail());
                    }
                });
    }

    private <T> Result<T> handleResponse(Response response, Function<ResponseBody, Result<T>> handleResponseBody) {
        try (var responseBody = response.body()) {
            if (!response.isSuccessful()) {
                return Result.failure("Data-plane responded with %d - %s. Response body: %s"
                        .formatted(response.code(), response.message(), responseBody.string()));
            }
            return handleResponseBody.apply(responseBody);

        } catch (IOException e) {
            return Result.failure("Data-plane responded with %d - %s. Cannot read response body: %s"
                    .formatted(response.code(), response.message(), e.getMessage()));
        }
    }

    private Result<Void> discardResponseBody(ResponseBody responseBody) {
        return Result.success();
    }

    private Result<DataFlowStatusMessage> dataFlowStatusMessage(ResponseBody responseBody, boolean preparation) {
        try {
            var mapper = objectMapperSupplier.get();
            var json = mapper.readTree(responseBody.byteStream());

            // Accept the old custom response as well as the standard EDC 0.18
            // DataFlowResponseMessage returned by the dataplane API.
            var state = text(json, "state");
            if (state == null) {
                var provisioning = json.path(EDC_NAMESPACE + "provisioning").asBoolean(false);
                state = provisioning
                        ? (preparation ? "PROVISIONING" : "STARTING")
                        : (preparation ? "PROVISIONED" : "STARTED");
            }

            var dataAddress = json.get(EDC_NAMESPACE + "dataAddress");
            if (dataAddress == null) {
                dataAddress = json.get("dataAddress");
            }

            var builder = DataFlowStatusMessage.Builder.newInstance().state(state);
            if (dataAddress != null && !dataAddress.isNull()) {
                builder.dataAddress(toDspDataAddress(dataAddress));
            }
            var error = text(json, "error");
            if (error != null) {
                builder.error(error);
            }
            return Result.success(builder.build());
        } catch (IOException e) {
            return Result.failure("Cannot parse data-plane response: " + e.getMessage());
        }
    }

    private StatusResult<Request.Builder> createRequestBuilder(Object message, String url) {
        return this.serialize(message)
                .map(rawBody -> RequestBody.create(rawBody, TYPE_JSON))
                .map(body -> new Request.Builder().post(body).url(url))
                .compose(this::setupAuthorization)
                .flatMap(it -> {
                    if (it.succeeded()) {
                        return StatusResult.success(it.getContent());
                    } else {
                        return StatusResult.failure(FATAL_ERROR, it.getFailureDetail());
                    }
                });
    }

    private Result<Request.Builder> setupAuthorization(Request.Builder requestBuilder) {
        var authorizationProfile = dataPlane.getAuthorizationProfile();
        if (authorizationProfile == null) {
            return Result.success(requestBuilder);
        }
        var authorization = authorizationRegistry.findByType(authorizationProfile.type());
        if (authorization == null) {
            return Result.failure("Authorization %s not supported".formatted(authorizationProfile.type()));
        }

        return authorization
                .evaluate(authorizationProfile)
                .map(header -> requestBuilder.addHeader(header.key(), header.value()));
    }

    private Result<String> serialize(Object message) {
        try {
            var mapper = objectMapperSupplier.get();
            return Result.success(mapper.writeValueAsString(toStandardMessage(message, mapper)));
        } catch (RuntimeException | IOException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * The EDC 0.18 dataplane API consumes the standard expanded JSON-LD
     * DataFlowProvisionMessage/DataFlowStartMessage format. The in-tree
     * signaling domain predates that API and is intentionally kept as the
     * internal model, so convert it at this HTTP boundary.
     */
    private ObjectNode toStandardMessage(Object message, ObjectMapper mapper) {
        var json = mapper.createObjectNode();
        var context = json.putObject(JSON_LD_CONTEXT);
        context.put("@vocab", EDC_NAMESPACE);

        if (message instanceof DataFlowPrepareMessage prepare) {
            json.put(JSON_LD_TYPE, EDC_NAMESPACE + "DataFlowProvisionMessage");
            addCommon(json, prepare.getAgreementId(), prepare.getDataFlowId(), prepare.getDatasetId(), prepare.getParticipantId());
            add(json, EDC_NAMESPACE + "callbackAddress", prepare.getCallbackAddress() == null ? null : prepare.getCallbackAddress().toString());
            addTransferType(json, prepare.getProfile());
            addMap(json, EDC_NAMESPACE + "properties", prepare.getMetadata(), mapper);
            addDataAddress(json, EDC_NAMESPACE + "destination", prepare.getDataAddress());
        } else if (message instanceof DataFlowStartMessage start) {
            json.put(JSON_LD_TYPE, EDC_NAMESPACE + "DataFlowStartMessage");
            addCommon(json, start.getAgreementId(), start.getDataFlowId(), start.getDatasetId(), start.getParticipantId());
            add(json, EDC_NAMESPACE + "callbackAddress", start.getCallbackAddress() == null ? null : start.getCallbackAddress().toString());
            addTransferType(json, start.getProfile());
            addDataAddress(json, EDC_NAMESPACE + "sourceDataAddress", start.getDataAddress());
            addMap(json, EDC_NAMESPACE + "properties", start.getMetadata(), mapper);
        } else if (message instanceof DataFlowSuspendMessage suspend) {
            json.put(JSON_LD_TYPE, EDC_NAMESPACE + "DataFlowSuspendMessage");
            add(json, EDC_NAMESPACE + "reason", suspend.getReason());
        } else if (message instanceof DataFlowTerminateMessage) {
            json.put(JSON_LD_TYPE, EDC_NAMESPACE + "DataFlowTerminateMessage");
        } else if (message instanceof DataFlowResumeMessage resume) {
            // The 0.18 API has no resume message; retain the standard start
            // shape for deployments that expose the compatibility endpoint.
            json.put(JSON_LD_TYPE, EDC_NAMESPACE + "DataFlowStartMessage");
            addDataAddress(json, EDC_NAMESPACE + "destinationDataAddress", resume.getDataAddress());
        } else if (message instanceof DataFlowStartedNotificationMessage started) {
            json.put(JSON_LD_TYPE, EDC_NAMESPACE + "DataFlowStartMessage");
            addDataAddress(json, EDC_NAMESPACE + "sourceDataAddress", started.getDataAddress());
        } else if (message instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    json.set(String.valueOf(key), mapper.valueToTree(value));
                }
            });
        } else {
            throw new IllegalArgumentException("Unsupported data-plane message: " + message.getClass().getName());
        }

        return json;
    }

    private void addCommon(ObjectNode json, String agreementId, String processId, String assetId, String participantId) {
        add(json, EDC_NAMESPACE + "agreementId", agreementId);
        add(json, EDC_NAMESPACE + "processId", processId);
        add(json, EDC_NAMESPACE + "datasetId", assetId);
        add(json, EDC_NAMESPACE + "participantId", participantId);
    }

    private void addTransferType(ObjectNode json, String profile) {
        if (profile == null || profile.isBlank()) {
            return;
        }
        var flowType = profile.endsWith("-PUSH") ? "PUSH" : "PULL";
        var destination = profile.endsWith("-PUSH") || profile.endsWith("-PULL")
                ? profile.substring(0, profile.length() - 5)
                : profile;
        add(json, EDC_NAMESPACE + "transferType", destination + "-" + flowType);
        add(json, EDC_NAMESPACE + "transferTypeDestination", destination);
        add(json, EDC_NAMESPACE + "flowType", flowType);
    }

    private void addDataAddress(ObjectNode json, String key, DspDataAddress address) {
        if (address == null) {
            return;
        }
        var target = json.putObject(key);
        target.put(JSON_LD_TYPE, EDC_NAMESPACE + "DataAddress");
        add(target, EDC_NAMESPACE + "type", address.getEndpointType());
        add(target, EDC_NAMESPACE + "endpoint", address.getEndpoint());
        address.getEndpointProperties().forEach(property -> {
            if (property.getName() != null && property.getValue() != null) {
                var name = property.getName().contains("://")
                        ? property.getName()
                        : EDC_NAMESPACE + property.getName();
                add(target, name, property.getValue());
            }
        });
    }

    private void addMap(ObjectNode json, String key, Map<String, Object> values, ObjectMapper mapper) {
        if (values != null && !values.isEmpty()) {
            json.set(key, mapper.valueToTree(values));
        }
    }

    private void add(ObjectNode json, String key, String value) {
        if (value != null) {
            json.put(key, value);
        }
    }

    private String text(JsonNode node, String key) {
        var value = node.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    private DspDataAddress toDspDataAddress(JsonNode node) {
        var builder = DspDataAddress.Builder.newInstance()
                .endpointType(text(node, EDC_NAMESPACE + "type"))
                .endpoint(text(node, EDC_NAMESPACE + "endpoint"));
        var properties = node.get(EDC_NAMESPACE + "properties");
        if (properties != null && properties.isObject()) {
            addProperties(builder, properties);
        }
        node.fields().forEachRemaining(entry -> {
            var key = entry.getKey();
            if (!JSON_LD_TYPE.equals(key) && !JSON_LD_CONTEXT.equals(key)
                    && !key.equals(EDC_NAMESPACE + "type")
                    && !key.equals(EDC_NAMESPACE + "endpoint")
                    && !key.equals(EDC_NAMESPACE + "properties")) {
                addProperty(builder, key, entry.getValue());
            }
        });
        return builder.build();
    }

    private void addProperties(DspDataAddress.Builder builder, JsonNode properties) {
        properties.fields().forEachRemaining(entry -> addProperty(builder, entry.getKey(), entry.getValue()));
    }

    private void addProperty(DspDataAddress.Builder builder, String key, JsonNode value) {
        if (value != null && !value.isNull() && value.isValueNode()) {
            builder.property(key, value.asText());
        }
    }
}
