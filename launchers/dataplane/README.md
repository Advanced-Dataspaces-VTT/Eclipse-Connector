# Dataplane launcher

This launcher packages the EDC 0.18 HTTP and S3 dataplane modules. Its
self-registration client is patched to use the current data-plane-signaling
registration message with `PUT /v4/dataplanes`. This avoids the `POST` and
legacy `DataPlaneInstance` payload used by the published 0.18 selector client.

The control plane must expose the data-plane-signaling registration API at the
configured selector URL.

Create the runtime-only Kubernetes Secret before deploying the dataplane. The
value must include the scheme, not just the token:

```bash
kubectl -n gx-participant create secret generic gx-participant-dataplane-auth \
  --from-literal=authorization="Bearer ${GXTOKEN}" \
  --dry-run=client -o yaml | kubectl apply -f -
```

The deployment reads that value as `edc.controlplane.api.auth.value` and sends
it as the `Authorization` header to the control plane during self-registration.
The token must be accepted by the control plane's delegated management API,
including the configured `connector-api` audience and the dataplane management
scope. Because a Keycloak access token expires, recreate or update this Secret
and restart the dataplane when the token is renewed:

```bash
kubectl -n gx-participant rollout restart deployment/gx-participant-dataplane
```

Build and publish the image from the repository root:

```bash
./gradlew --no-daemon :launchers:dataplane:verifyServiceDescriptors
docker build -f Dockerfile.dataplane -t ghcr.io/data-space-core/dataplane:<tag> .
docker push ghcr.io/data-space-core/dataplane:<tag>
```
