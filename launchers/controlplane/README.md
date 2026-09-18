# DIL connector control-plane image

This launcher packages the EDC control plane with DSP HTTP APIs, DCP support,
data-plane signaling, and the data-plane selector in one executable image.

It also includes PostgreSQL stores, SQL schema bootstrapping, and HashiCorp
Vault. Configure the datasource and Vault URL/token at deployment time. The
container reads `/app/configuration.properties` by default (`EDC_FS_CONFIG`
overrides this).

Service descriptors must be included before Shadow merges them. The Docker
build runs `:launchers:controlplane:verifyServiceDescriptors`, which checks
that every EDC extension registered by the runtime dependencies survives in
the assembled JAR. This prevents a successful build with missing runtime
services.

This source tree is EDC `1.0.0-SNAPSHOT`. It does not contain the data-plane
framework or HTTP/S3 pipeline implementations. Do not add the removed
`:dist:bom:dataplane-base-bom` project or mix `0.18.0` data-plane artifacts
into this launcher: that produces incompatible selector service types and the
missing `DataPlaneSelectorService` errors seen at startup.

For HTTP pull/push and S3 transfers, deploy a data-plane runtime built from a
matching EDC source/release and register it with this control plane. A true
single-process HTTP/S3 image requires restoring the data-plane source modules
from the same EDC revision, not adding the old 0.18 BOM to this checkout.

Build locally from the repository root:

```shell
docker build -f Dockerfile.controlplane -t controlplane:local .
```

The GitHub Actions workflow publishes the image as
`ghcr.io/<repository-owner>/controlplane:latest` on pushes to `main`.

The current image contains the selector, not a public data-plane listener on
port `11001`; keep that listener on the separately deployed data-plane.

```shell
docker tag controlplane:local   ghcr.io/data-space-core/controlplane:<tag>
docker push ghcr.io/data-space-core/controlplane:<tag>
```
