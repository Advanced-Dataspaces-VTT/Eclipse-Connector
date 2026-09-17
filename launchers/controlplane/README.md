# DIL connector image

This launcher packages the EDC control plane with DSP HTTP APIs, DCP support,
data-plane signaling, and the EDC 0.18 data-plane runtime in one executable
image.

It also includes PostgreSQL stores, SQL schema bootstrapping, and HashiCorp
Vault. Configure the datasource and Vault URL/token at deployment time. The
container reads `/app/configuration.properties` by default (`EDC_FS_CONFIG`
overrides this). Configure distinct web, management, protocol, control, and
    public data-plane ports, as in the GX participant deployment. The public
    data-plane endpoint is `/api/public` on port `11001`.

Service descriptors must be included before Shadow merges them. The Docker
build runs `:launchers:controlplane:verifyServiceDescriptors`, which checks
that every EDC extension registered by the runtime dependencies survives in
the assembled JAR. This prevents a successful build with missing runtime
services.

This source tree is EDC `1.0.0-SNAPSHOT`; it is not a guaranteed drop-in
replacement for older participant deployments. Validate DSP/DCP, dataplane
signaling, and database schema compatibility before updating a live tenant.

The HTTP and S3 transport implementations are embedded from the published EDC
0.18.0 artifacts. HTTP pull/push and S3 source/sink transfers are selected by
the normal EDC data-plane transfer type and data-address validators.

Build locally from the repository root:

```shell
docker build -f Dockerfile.controlplane -t controlplane:local .
```

The GitHub Actions workflow publishes the image as
`ghcr.io/<repository-owner>/controlplane:latest` and
`ghcr.io/<repository-owner>/controlplane:edc-018-dataplane-s3` on pushes to
`main`.
```shell
docker tag controlplane:local   ghcr.io/data-space-core/controlplane:<tag>
docker push ghcr.io/data-space-core/controlplane:<tag>
````
