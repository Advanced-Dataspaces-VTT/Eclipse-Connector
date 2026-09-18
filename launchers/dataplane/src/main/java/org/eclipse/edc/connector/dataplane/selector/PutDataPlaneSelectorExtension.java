/*
 *  Copyright (c) 2026 Advanced Dataspaces VTT
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 */

package org.eclipse.edc.connector.dataplane.selector;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.connector.dataplane.selector.spi.DataPlaneSelectorService;
import org.eclipse.edc.connector.dataplane.selector.spi.instance.AuthorizationProfile;
import org.eclipse.edc.connector.dataplane.selector.spi.instance.DataPlaneInstance;
import org.eclipse.edc.http.spi.ControlApiHttpClient;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Provides;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.system.ServiceExtension;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static jakarta.json.JsonValue.NULL;

/**
 * Compatibility implementation for the EDC 0.18 dataplane selector client.
 *
 * EDC 0.18 sends a legacy DataPlaneInstance with POST. Current EDC control
 * planes expose the data-plane-signaling registration resource, which accepts
 * DataPlaneRegistrationMessage with PUT at the same v4 URL.
 */
@Extension(PutDataPlaneSelectorExtension.NAME)
@Provides(DataPlaneSelectorService.class)
public class PutDataPlaneSelectorExtension implements ServiceExtension {

    public static final String NAME = "PUT DataPlane Selector client";
    private static final MediaType JSON = MediaType.parse("application/json");

    @Setting(key = "edc.dpf.selector.url", description = "Control plane dataplane registration URL")
    private String selectorApiUrl;

    @Setting(
            key = "edc.dpf.compatibility.transfer.types",
            description = "Additional transfer profiles advertised by this dataplane, comma separated",
            defaultValue = "s3-copy"
    )
    private String compatibilityTransferTypes;

    @Setting(key = "edc.dpf.compatibility.authorization.type", defaultValue = "oauth2_client_credentials")
    private String compatibilityAuthorizationType;

    @Setting(key = "edc.dpf.compatibility.authorization.token.endpoint", required = false)
    private String compatibilityTokenEndpoint;

    @Setting(key = "edc.dpf.compatibility.authorization.client.id", required = false)
    private String compatibilityClientId;

    @Setting(key = "edc.dpf.compatibility.authorization.client.secret", required = false)
    private String compatibilityClientSecret;

    @Inject
    private ControlApiHttpClient httpClient;

    @Provider
    public DataPlaneSelectorService dataPlaneSelectorService() {
        return new PutDataPlaneSelectorService(httpClient, selectorApiUrl, compatibilityTransferTypes,
                compatibilityAuthorizationType, compatibilityTokenEndpoint, compatibilityClientId,
                compatibilityClientSecret);
    }

    private static final class PutDataPlaneSelectorService implements DataPlaneSelectorService {
        private final ControlApiHttpClient httpClient;
        private final String selectorApiUrl;
        private final Set<String> compatibilityTransferTypes;
        private final String compatibilityAuthorizationType;
        private final String compatibilityTokenEndpoint;
        private final String compatibilityClientId;
        private final String compatibilityClientSecret;

        private PutDataPlaneSelectorService(ControlApiHttpClient httpClient, String selectorApiUrl, String compatibilityTransferTypes,
                                            String compatibilityAuthorizationType, String compatibilityTokenEndpoint,
                                            String compatibilityClientId, String compatibilityClientSecret) {
            this.httpClient = httpClient;
            this.selectorApiUrl = selectorApiUrl;
            this.compatibilityTransferTypes = Arrays.stream(compatibilityTransferTypes.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            this.compatibilityAuthorizationType = compatibilityAuthorizationType;
            this.compatibilityTokenEndpoint = compatibilityTokenEndpoint;
            this.compatibilityClientId = compatibilityClientId;
            this.compatibilityClientSecret = compatibilityClientSecret;
        }

        @Override
        public ServiceResult<List<DataPlaneInstance>> getAll() {
            return unsupported("getAll is not available from a standalone dataplane");
        }

        @Override
        public ServiceResult<List<DataPlaneInstance>> search(QuerySpec querySpec) {
            return unsupported("search is not available from a standalone dataplane");
        }

        @Override
        public ServiceResult<DataPlaneInstance> select(String strategy, Predicate<DataPlaneInstance> predicate) {
            return unsupported("select is not available from a standalone dataplane");
        }

        @Override
        public ServiceResult<DataPlaneInstance> selectFor(TransferProcess transferProcess) {
            return unsupported("selectFor is not available from a standalone dataplane");
        }

        @Override
        public ServiceResult<Void> register(DataPlaneInstance instance) {
            var body = registrationBody(instance).toString();
            var request = new Request.Builder()
                    .url(selectorApiUrl)
                    .put(RequestBody.create(body, JSON));
            return httpClient.request(request).mapEmpty();
        }

        @Override
        public ServiceResult<Void> unregister(String dataplaneId) {
            return delete(dataplaneId);
        }

        @Override
        public ServiceResult<Void> delete(String dataplaneId) {
            var request = new Request.Builder()
                    .url(selectorApiUrl + "/" + dataplaneId)
                    .delete();
            return httpClient.request(request).mapEmpty();
        }

        @Override
        public ServiceResult<DataPlaneInstance> findById(String dataplaneId) {
            return unsupported("findById is not available from a standalone dataplane");
        }

        private JsonObject registrationBody(DataPlaneInstance instance) {
            var transferTypes = new LinkedHashSet<String>();
            instance.getAllowedTransferTypes().forEach(transferTypes::add);
            transferTypes.addAll(compatibilityTransferTypes);

            var builder = Json.createObjectBuilder()
                    .add("dataplaneId", instance.getId())
                    .add("endpoint", instance.getUrl().toString())
                    .add("transferTypes", strings(transferTypes))
                    .add("labels", strings(instance.getLabels()));

            var authorization = authorization(instance.getAuthorizationProfile());
            if (authorization == null) {
                authorization = configuredAuthorization();
            }
            if (authorization != null) {
                builder.add("authorization", authorization);
            } else {
                builder.add("authorization", NULL);
            }
            return builder.build();
        }

        private JsonObject configuredAuthorization() {
            if (compatibilityTokenEndpoint == null || compatibilityClientId == null || compatibilityClientSecret == null) {
                return null;
            }
            return Json.createObjectBuilder()
                    .add("type", compatibilityAuthorizationType)
                    .add("tokenEndpoint", compatibilityTokenEndpoint)
                    .add("clientId", compatibilityClientId)
                    .add("clientSecret", compatibilityClientSecret)
                    .build();
        }

        private static JsonArrayBuilder strings(Iterable<String> values) {
            var array = Json.createArrayBuilder();
            values.forEach(array::add);
            return array;
        }

        private static JsonObject authorization(AuthorizationProfile profile) {
            if (profile == null) {
                return null;
            }

            var builder = Json.createObjectBuilder();
            profile.properties().forEach((key, value) -> add(builder, key, value));
            if (!profile.properties().containsKey("type")) {
                builder.add("type", profile.type());
            }
            return builder.build();
        }

        private static void add(JsonObjectBuilder builder, String key, Object value) {
            if (value == null) {
                builder.add(key, NULL);
            } else if (value instanceof String string) {
                builder.add(key, string);
            } else if (value instanceof Boolean bool) {
                builder.add(key, bool);
            } else if (value instanceof Integer integer) {
                builder.add(key, integer);
            } else if (value instanceof Long longValue) {
                builder.add(key, longValue);
            } else if (value instanceof Number number) {
                builder.add(key, number.doubleValue());
            } else {
                builder.add(key, value.toString());
            }
        }

        private static <T> ServiceResult<T> unsupported(String message) {
            return ServiceResult.unexpected(message);
        }
    }
}
