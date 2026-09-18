/*
 * Copyright (c) 2026 Advanced Dataspaces VTT
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.eclipse.edc.connector.dataplane.s3;

import org.eclipse.dataplane.Dataplane;
import org.eclipse.dataplane.domain.DataAddress;
import org.eclipse.dataplane.domain.Result;
import org.eclipse.dataplane.domain.dataflow.DataFlow;
import org.eclipse.dataplane.domain.registration.AuthorizationProfile;
import org.eclipse.dataplane.domain.registration.ControlPlaneRegistrationMessage;
import org.eclipse.dataplane.port.DataPlaneRegistrationApiController;
import org.eclipse.dataplane.port.DataPlaneSignalingApiController;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Data Plane Signaling implementation for the GX consumer dataplane.
 *
 * <p>The control plane first prepares the consumer destination. The provider
 * then sends the source address in the DSP started notification. The SDK
 * exposes those as separate lifecycle events, so the destination is retained
 * by flow id until the source arrives. For a pull flow, the dataplane copies
 * the provider S3 object into that destination and reports completion to the
 * control plane.</p>
 */
@Extension(NativeS3DataPlaneExtension.NAME)
public class NativeS3DataPlaneExtension implements ServiceExtension {

    public static final String NAME = "Native S3 Data Plane Signaling";

    private static final String OAUTH2_CLIENT_CREDENTIALS = "oauth2_client_credentials";
    private static final String AMAZON_S3 = "AmazonS3";

    @Setting(key = "edc.dataplane.id", defaultValue = "gx-participant1-dataplane")
    private String dataplaneId;

    @Setting(key = "edc.control.endpoint", defaultValue = "http://localhost:8083/api/control")
    private String controlEndpoint;

    @Setting(key = "edc.dpf.compatibility.authorization.type", defaultValue = OAUTH2_CLIENT_CREDENTIALS)
    private String authorizationType;

    @Setting(key = "edc.dpf.compatibility.authorization.token.endpoint", required = false)
    private String tokenEndpoint;

    @Setting(key = "edc.dpf.compatibility.authorization.client.id", required = false)
    private String clientId;

    @Setting(key = "edc.dpf.compatibility.authorization.client.secret", required = false)
    private String clientSecret;

    @Setting(key = "edc.dpf.compatibility.authorization.audience", required = false)
    private String audience;

    @Setting(key = "edc.dpf.compatibility.controlplane.id", required = false)
    private String controlplaneId;

    @Setting(key = "edc.dpf.compatibility.controlplane.endpoint", required = false)
    private String controlplaneEndpoint;

    @Inject
    private WebService webService;

    @Inject
    private Monitor monitor;

    private final Map<String, DataAddress> destinations = new ConcurrentHashMap<>();
    private final ExecutorService transfers = Executors.newFixedThreadPool(2);
    private Dataplane dataplane;

    @Override
    public void initialize(ServiceExtensionContext context) {
        var authorization = authorizationProfile();
        var builder = Dataplane.newInstance()
                .id(dataplaneId)
                .endpoint(URI.create(controlEndpoint + "/v1/dataflows"))
                .authorizationProfile(authorization)
                .registerAuthorization(new NativeOauth2ClientCredentialsAuthorization())
                .profile("s3-copy-PULL")
                .profile("s3-copy-PUSH")
                .onPrepare(this::prepare)
                .onStart(this::start)
                .onStarted(this::started)
                .onCompleted(flow -> {
                    destinations.remove(flow.getId());
                    return Result.success(flow);
                })
                .onTerminate(flow -> {
                    destinations.remove(flow.getId());
                    return Result.success(flow);
                });

        dataplane = builder.build();
        webService.registerResource(new DataPlaneSignalingApiController(dataplane));
        webService.registerResource(new DataPlaneRegistrationApiController(dataplane));

        registerControlPlane(context);
    }

    @Override
    public void shutdown() {
        transfers.shutdownNow();
    }

    private Result<DataFlow> prepare(DataFlow flow) {
        if (!flow.isPull()) {
            return Result.failure(new IllegalArgumentException("Only s3-copy-PULL is implemented by the GX consumer dataplane"));
        }
        if (flow.getDataAddress() == null) {
            return Result.failure(new IllegalArgumentException("The consumer destination address is missing"));
        }
        destinations.put(flow.getId(), flow.getDataAddress());
        monitor.info("Prepared native S3 pull flow %s for destination %s".formatted(flow.getId(), describe(flow.getDataAddress())));
        return Result.success(flow);
    }

    private Result<DataFlow> start(DataFlow flow) {
        // The provider source is delivered later by the /started notification.
        monitor.info("Received native S3 start for flow %s; waiting for source address".formatted(flow.getId()));
        return Result.success(flow);
    }

    private Result<DataFlow> started(DataFlow flow) {
        var destination = destinations.get(flow.getId());
        var source = flow.getDataAddress();
        if (destination == null) {
            return Result.failure(new IllegalArgumentException("No prepared destination found for flow " + flow.getId()));
        }
        if (source == null) {
            return Result.failure(new IllegalArgumentException("The provider source address is missing for flow " + flow.getId()));
        }

        monitor.info("Starting native S3 copy for flow %s: source=%s destination=%s"
                .formatted(flow.getId(), describe(source), describe(destination)));
        transfers.execute(() -> copyAndNotify(flow.getId(), source, destination));
        return Result.success(flow);
    }

    private void copyAndNotify(String flowId, DataAddress source, DataAddress destination) {
        try {
            copy(source, destination);
            monitor.info("Native S3 copy completed for flow %s".formatted(flowId));
            var result = dataplane.notifyCompleted(flowId);
            if (result.failed()) {
                monitor.severe("Could not notify control plane that flow %s completed: %s"
                        .formatted(flowId, result.getException().getMessage()));
            }
        } catch (Exception error) {
            monitor.severe("Native S3 copy failed for flow %s: %s".formatted(flowId, error.getMessage()), error);
            var result = dataplane.notifyErrored(flowId, error);
            if (result.failed()) {
                monitor.severe("Could not notify control plane that flow %s failed: %s"
                        .formatted(flowId, result.getException().getMessage()));
            }
        }
    }

    private void copy(DataAddress source, DataAddress destination) throws Exception {
        var sourceConfig = s3Config(source);
        var destinationConfig = s3Config(destination);
        var sourceClient = client(sourceConfig);
        var destinationClient = client(destinationConfig);
        try {
            var head = sourceClient.headObject(HeadObjectRequest.builder()
                    .bucket(sourceConfig.bucket())
                    .key(sourceConfig.key())
                    .build());
            try (var input = sourceClient.getObject(GetObjectRequest.builder()
                    .bucket(sourceConfig.bucket())
                    .key(sourceConfig.key())
                    .build())) {
                destinationClient.putObject(PutObjectRequest.builder()
                                .bucket(destinationConfig.bucket())
                                .key(destinationConfig.key())
                                .contentType(head.contentType())
                                .build(),
                        RequestBody.fromInputStream(input, head.contentLength()));
            }
        } finally {
            sourceClient.close();
            destinationClient.close();
        }
    }

    private S3Client client(S3Address address) {
        var builder = S3Client.builder()
                .region(Region.of(address.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(address.accessKeyId(), address.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(address.pathStyle()).build());
        if (address.endpointOverride() != null) {
            builder.endpointOverride(URI.create(address.endpointOverride()));
        }
        return builder.build();
    }

    private S3Address s3Config(DataAddress address) {
        if (!AMAZON_S3.equalsIgnoreCase(address.getType())) {
            throw new IllegalArgumentException("Unsupported data address type: " + address.getType());
        }
        var values = new HashMap<String, String>();
        address.endpointProperties().forEach(property -> values.put(normalize(property.name()), property.value()));
        var bucket = required(values, "bucketname");
        var key = values.getOrDefault("objectname", values.get("keyname"));
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("S3 data address is missing objectName/keyName");
        }
        var endpoint = values.getOrDefault("endpointoverride", address.endpoint());
        var accessKeyId = required(values, "accesskeyid");
        var secretAccessKey = required(values, "secretaccesskey");
        return new S3Address(bucket, key, endpoint, values.getOrDefault("region", "us-east-1"),
                accessKeyId, secretAccessKey, Boolean.parseBoolean(values.getOrDefault("pathstyle", "true")));
    }

    private String required(Map<String, String> values, String key) {
        var value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("S3 data address is missing " + key);
        }
        return value;
    }

    private String normalize(String name) {
        if (name == null) {
            return "";
        }
        var normalized = name.toLowerCase(Locale.ROOT);
        var hash = normalized.lastIndexOf('#');
        var slash = normalized.lastIndexOf('/');
        return normalized.substring(Math.max(hash, slash) + 1).replace("_", "");
    }

    private String describe(DataAddress address) {
        var values = new HashMap<String, String>();
        address.endpointProperties().forEach(property -> values.put(normalize(property.name()), property.value()));
        return "%s://%s/%s%s".formatted(address.getType(), values.get("bucketname"),
                values.getOrDefault("objectname", values.get("keyname")),
                values.get("endpointoverride") == null ? "" : " @ " + values.get("endpointoverride"));
    }

    private AuthorizationProfile authorizationProfile() {
        var profile = new AuthorizationProfile(authorizationType);
        if (tokenEndpoint != null) {
            profile.withAttribute("tokenEndpoint", tokenEndpoint);
        }
        if (clientId != null) {
            profile.withAttribute("clientId", clientId);
        }
        if (clientSecret != null) {
            profile.withAttribute("clientSecret", clientSecret);
        }
        if (audience != null && !audience.isBlank()) {
            profile.withAttribute("audience", audience);
        }
        return profile;
    }

    private void registerControlPlane(ServiceExtensionContext context) {
        if (controlplaneId == null || controlplaneId.isBlank() || controlplaneEndpoint == null || controlplaneEndpoint.isBlank()) {
            throw new IllegalStateException("Native dataplane control-plane id and endpoint must be configured");
        }
        var result = dataplane.registerControlPlane(new ControlPlaneRegistrationMessage(
                controlplaneId, URI.create(controlplaneEndpoint), authorizationProfile()));
        if (result.failed()) {
            throw new IllegalStateException("Could not register native dataplane control plane: " + result.getException().getMessage());
        }
        context.getMonitor().info("Registered native dataplane with control plane %s".formatted(controlplaneId));
    }

    private record S3Address(String bucket, String key, String endpointOverride, String region,
                             String accessKeyId, String secretAccessKey, boolean pathStyle) {
    }
}
