/*
 * Copyright (c) 2026 Advanced Dataspaces VTT
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 */

package org.eclipse.edc.connector.dataplane.compat;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.edc.connector.dataplane.spi.DataFlow;
import org.eclipse.edc.connector.dataplane.spi.provision.ProvisionResource;
import org.eclipse.edc.connector.dataplane.spi.store.DataPlaneStore;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.spi.system.ServiceExtension;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;

import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;

/**
 * Bridges the EDC 0.18 provision/start lifecycle for pull transfers.
 *
 * The 0.18 dataplane persists a flow during provision with only its
 * destination. When the later start message contains the provider source,
 * the stock manager reuses the persisted flow but does not merge that source,
 * causing EndpointDataReferenceServiceRegistryImpl to receive null.
 */
@Extension(DataPlaneSourceAddressCompatibilityExtension.NAME)
public class DataPlaneSourceAddressCompatibilityExtension implements ServiceExtension {

    public static final String NAME = "Data Plane Source Address Compatibility";

    @Inject
    private WebService webService;

    @Inject
    private DataPlaneStore dataPlaneStore;

    @Inject
    private TypeTransformerRegistry transformerRegistry;

    @Override
    public void initialize(org.eclipse.edc.spi.system.ServiceExtensionContext context) {
        webService.registerResource("control", new SourceAddressMergeFilter(
                dataPlaneStore,
                transformerRegistry,
                context.getMonitor().withPrefix("DataPlaneSourceAddressCompatibility")
        ));
    }

    @Provider
    private static final class SourceAddressMergeFilter implements ContainerRequestFilter {
        private static final String PROCESS_ID = EDC_NAMESPACE + "processId";
        private static final String SOURCE_DATA_ADDRESS = EDC_NAMESPACE + "sourceDataAddress";

        private final DataPlaneStore dataPlaneStore;
        private final TypeTransformerRegistry transformerRegistry;
        private final Monitor monitor;

        private SourceAddressMergeFilter(DataPlaneStore dataPlaneStore,
                                         TypeTransformerRegistry transformerRegistry,
                                         Monitor monitor) {
            this.dataPlaneStore = dataPlaneStore;
            this.transformerRegistry = transformerRegistry;
            this.monitor = monitor;
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (!isStartRequest(requestContext) || !requestContext.hasEntity()) {
                return;
            }

            try {
                var body = requestContext.getEntityStream().readAllBytes();
                requestContext.setEntityStream(new ByteArrayInputStream(body));

                var json = Json.createReader(new StringReader(new String(body))).readObject();
                var processId = stringValue(json, PROCESS_ID);
                var sourceJson = json.getJsonObject(SOURCE_DATA_ADDRESS);
                if (processId == null || sourceJson == null) {
                    return;
                }

                var sourceResult = transformerRegistry.forContext("signaling-api")
                        .transform(sourceJson, DataAddress.class);
                if (sourceResult.failed()) {
                    monitor.warning("Could not transform sourceDataAddress for flow %s: %s"
                            .formatted(processId, sourceResult.getFailureDetail()));
                    return;
                }

                var flow = dataPlaneStore.findById(processId);
                if (flow == null || flow.getSource() != null) {
                    return;
                }

                var updated = copyWithSource(flow, sourceResult.getContent());
                var saveResult = dataPlaneStore.save(updated);
                if (saveResult.failed()) {
                    throw new InternalServerErrorException(
                            "Could not persist sourceDataAddress for data flow " + processId
                    );
                }

                monitor.debug("Merged sourceDataAddress into provisioned data flow %s (type=%s)"
                        .formatted(processId, sourceResult.getContent().getType()));
            } catch (InternalServerErrorException e) {
                throw e;
            } catch (Exception e) {
                throw new InternalServerErrorException(
                        "Could not process sourceDataAddress for data flow start", e
                );
            }
        }

        private static boolean isStartRequest(ContainerRequestContext context) {
            return context.getMethod().equalsIgnoreCase("POST")
                    && context.getUriInfo().getPath().endsWith("/dataflows/start")
                    && MediaType.APPLICATION_JSON_TYPE.isCompatible(context.getMediaType());
        }

        private static String stringValue(JsonObject object, String key) {
            var value = object.get(key);
            return value instanceof JsonString string ? string.getString() : null;
        }

        private static DataFlow copyWithSource(DataFlow flow, DataAddress source) {
            return DataFlow.Builder.newInstance()
                    .id(flow.getId())
                    .createdAt(flow.getCreatedAt())
                    .state(flow.getState())
                    .stateCount(flow.getStateCount())
                    .stateTimestamp(flow.getStateTimestamp())
                    .updatedAt(flow.getUpdatedAt())
                    .traceContext(flow.getTraceContext())
                    .errorDetail(flow.getErrorDetail())
                    .pending(flow.isPending())
                    .source(source)
                    .destination(flow.getDestination())
                    .callbackAddress(flow.getCallbackAddress())
                    .properties(flow.getProperties())
                    .transferType(flow.getTransferType())
                    .runtimeId(flow.getRuntimeId())
                    .resourceDefinitions(new ArrayList<ProvisionResource>(flow.getResourceDefinitions()))
                    .participantId(flow.getParticipantId())
                    .assetId(flow.getAssetId())
                    .agreementId(flow.getAgreementId())
                    .build();
        }
    }
}
