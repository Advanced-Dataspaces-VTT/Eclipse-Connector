# EDC Catalog Test Script

`get_catalog.py` requests a remote DSP catalog through the EDC v5 Management API. It uses only the Python standard library and does not require `pip` dependencies.

The script calls:

```text
POST <management-url>/v5/participants/<url-encoded-participant-context-id>/catalog/request
```

The request body contains the remote participant DID and DSP URL:

```json
{
  "@context": [
    "https://w3id.org/edc/connector/management/v2",
    "https://w3id.org/dspace/2025/1/context.jsonld"
  ],
  "@type": "CatalogRequest",
  "profile": "http-dsp-profile-2025-1",
  "counterPartyId": "did:web:provider.example",
  "counterPartyAddress": "https://provider.example/api/dsp"
}
```

## Command line

From the Eclipse Connector checkout:

```bash
python3 test_scripts/get_catalog.py \
  --management-url https://connector.gx-participant1.dil.collab-cloud.eu/api/management \
  --participant-context-id did:web:gx-participant1.dil.collab-cloud.eu:identity \
  --remote-url https://dil-connector.material.dil.collab-cloud.eu/api/dsp \
  --remote-did did:web:dil-connector.material.dil.collab-cloud.eu:identity \
  --bearer-token "$KEYCLOAK_ACCESS_TOKEN"
```

For the legacy static EDC API authentication:

```bash
python3 test_scripts/get_catalog.py \
  --management-url https://connector.example/api/management \
  --participant-context-id did:web:participant.example:identity \
  --remote-url https://provider.example/api/dsp \
  --remote-did did:web:provider.example \
  --api-key "$EDC_API_KEY"
```

## Obtain a Keycloak access token

For automated testing, use a confidential Keycloak client with **Service accounts enabled**. The client must have the `management-api:catalog:read` client scope assigned as a default scope, and the `connector-api` audience mapper must add `connector-api` to the access token.

Set these values in the shell rather than putting secrets directly in the command:

```bash
export KEYCLOAK_BASE_URL="https://dil.collab-cloud.eu/auth"
export KEYCLOAK_REALM="gx-participant1"
export KEYCLOAK_CLIENT_ID="connector-api-client"
export KEYCLOAK_CLIENT_SECRET="replace-with-client-secret"
```

The same request can be made with the bundled helper and a JSON file:

```bash
cp test_scripts/keycloak.json /tmp/keycloak.json
$EDITOR /tmp/keycloak.json
python3 test_scripts/get-token.py --config /tmp/keycloak.json
```

The helper prints only the access token by default. To keep the client secret out of the JSON file, leave its value empty and provide it through the environment:

```bash
export KEYCLOAK_CLIENT_SECRET="replace-with-client-secret"
python3 test_scripts/get-token.py --config /tmp/keycloak.json
```

To save the token for the catalog test:

```bash
python3 test_scripts/get-token.py \
  --config /tmp/keycloak.json \
  --output /tmp/edc-access-token
python3 test_scripts/get_catalog.py \
  --config /tmp/catalog-request.json \
  --bearer-token "$(cat /tmp/edc-access-token)"
```

Use `--json` when the expiry and token type are also needed:

```bash
python3 test_scripts/get-token.py --config /tmp/keycloak.json --json
```

Inspect a token already stored in a shell variable:

```bash
python3 test_scripts/check-token.py "$KEYCLOAK_ACCESS_TOKEN"
```

The checker prints the JWT header and the relevant claims, then validates the default GX settings:

```text
iss: https://dil.collab-cloud.eu/auth/realms/gx-participant1
aud: connector-api
scope: management-api:catalog:read
```

For an elevated test client, also require the admin scope:

```bash
python3 test_scripts/check-token.py "$KEYCLOAK_ACCESS_TOKEN" \
  --scope management-api:catalog:read \
  --scope management-api:admin
```

The checker validates claims only; it does not verify the JWT signature against Keycloak JWKS. A token can pass this local inspection and still be rejected if the EDC cannot reach the configured JWKS endpoint or the signing key is not trusted.

Request a token with Keycloak's client-credentials flow:

```bash
export KEYCLOAK_ACCESS_TOKEN="$(curl -fsS -X POST \
  "$KEYCLOAK_BASE_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode "client_id=$KEYCLOAK_CLIENT_ID" \
  --data-urlencode "client_secret=$KEYCLOAK_CLIENT_SECRET" \
  --data-urlencode 'scope=openid management-api:catalog:read' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"
```

Inspect the non-secret token claims to confirm the configuration:

```bash
python3 - <<'PY'
import base64
import json
import os

token = os.environ["KEYCLOAK_ACCESS_TOKEN"]
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload))
print(json.dumps({key: claims.get(key) for key in ("iss", "aud", "scope", "sub")}, indent=2))
PY
```

The output should show the tenant realm as `iss`, include `connector-api` in `aud`, and include `management-api:catalog:read` in `scope`.

Use the token with the catalog test:

```bash
python3 test_scripts/get_catalog.py \
  --config /tmp/catalog-request.json \
  --bearer-token "$KEYCLOAK_ACCESS_TOKEN"
```

If the client uses browser login instead of client credentials, obtain the access token through the Keycloak authorization-code/PKCE flow and pass that token to the script. Do not use an ID token; EDC expects an access token.

The bearer-token mode sends `Authorization: Bearer <token>`. The API-key mode sends `X-Api-Key: <key>`. Do not provide both.

## JSON configuration

Copy the example and replace the authorization value:

```bash
cp test_scripts/catalog-request.example.json /tmp/catalog-request.json
$EDITOR /tmp/catalog-request.json
python3 test_scripts/get_catalog.py --config /tmp/catalog-request.json
```

The supported JSON fields are:

| Field | Required | Description |
| --- | --- | --- |
| `management_url` | yes | EDC Management API base URL, normally ending in `/api/management` |
| `participant_context_id` | yes | Local participant context used in the v5 URL |
| `remote_url` | yes | Remote connector DSP endpoint |
| `remote_did` | yes | Remote participant DID |
| `authorization.api_key` | no | Sends `X-Api-Key` |
| `authorization.bearer_token` | no | Sends `Authorization: Bearer ...` |
| `authorization.header` | no | Sends a complete custom `Authorization` value |
| `profile` | no | Registered EDC profile ID; defaults to `http-dsp-profile-2025-1` (the bundled DSP 2025/1 HTTP profile) |
| `additional_scopes` | no | DCP credential scopes requested for the outbound DSP call; repeatable in CLI as `--additional-scope` |
| `context` | no | Defaults to the DSPACE 2025-1 JSON-LD context |
| `timeout_seconds` | no | Defaults to 30 seconds |
| `insecure_tls` | no | Defaults to `false`; use only for local testing |

`participant_context_id` is different from `remote_did`: the former selects the local EDC tenant, while the latter identifies the remote connector being queried.

For a virtual EDC control plane, the requested profile must also be available to the participant context. The GX deployment enables all bundled profiles with `EDC_DATASPACE_ENABLE_PROFILES_ALL=true`. Alternatively, associate `http-dsp-profile-2025-1` through the participant-context Management API.

The GX deployment also configures `org.eclipse.dspace.dcp.vc.type:MembershipCredential:read` as a default DCP scope. This allows the connector to obtain the credential needed for the remote DSP request. Use `additional_scopes` only when the remote connector requires another credential scope.

## Save the response

```bash
python3 test_scripts/get_catalog.py \
  --config /tmp/catalog-request.json \
  --output /tmp/material-catalog.json
```

The script prints the HTTP status to stderr. Exit codes are `0` for a successful 2xx response, `1` for an HTTP error, and `2` for a connection or timeout failure.

## List datasets

`get_datasets.py` requests a catalog and prints only its datasets. Use `--output` to save the complete catalog for a later contract request:

```bash
python3 test_scripts/get_datasets.py \
  --config /tmp/catalog-request.json \
  --output /tmp/material-catalog.json
```

The output includes each dataset `@id`, title, description, policy offer, and distribution. The dataset ID is required for negotiation.

## Request a contract

First save a catalog with `get_catalog.py` or `get_datasets.py`, then select a dataset ID from the catalog:

```bash
python3 test_scripts/request_contract.py \
  --config /tmp/catalog-request.json \
  --catalog /tmp/material-catalog.json \
  --dataset-id urn:uuid:1ef99ee3-b156-49a0-91aa-343738ff21e1 \
  --output /tmp/contract-response.json
```

The script sends the dataset's first `hasPolicy` offer to:

```text
POST <management-url>/v5/participants/<participant-context-id>/contractnegotiations
```

It prints a response containing the negotiation `@id`. Save that ID for the status check. The catalog's first service `endpointURL` is used as the remote DSP address; override it with `--remote-url` when needed.

## Check contract state

```bash
python3 check_contract.py \
  --config /tmp/catalog-request.json \
  --negotiation-id <contract-negotiation-id>
```

This calls the v5 state endpoint:

```text
GET <management-url>/v5/participants/<participant-context-id>/contractnegotiations/<negotiation-id>/state
```

Run the command again while negotiation proceeds. A completed negotiation normally reports an agreed/finalized state and can then be used for a transfer-process request.

## Initiate a data transfer

Transfers are initiated through the **consumer** connector's EDC Management
API after the contract negotiation is in `AGREED` or `VERIFIED` state. The
transfer request uses the EDC contract agreement ID, not the dataset ID, offer
ID, or negotiation ID.

You can provide the agreement ID directly:

```bash
python3 test_scripts/request_transfer.py \
  --config /tmp/catalog-request.json \
  --contract-id "agreement:bb359e64-306c-418d-8ab2-bd76f40dbb42" \
  --asset-id urn:uuid:1ef99ee3-b156-49a0-91aa-343738ff21e1 \
  --counter-party-address https://dil-connector.material.dil.collab-cloud.eu/api/dsp \
  --transfer-type s3-copy \
  --output /tmp/transfer-response.json
```

Alternatively, let the script look up the agreement ID from the negotiation:

```bash
python3 test_scripts/request_transfer.py \
  --config /tmp/catalog-request.json \
  --negotiation-id bb359e64-306c-418d-8ab2-bd76f40dbb42 \
  --asset-id urn:uuid:1ef99ee3-b156-49a0-91aa-343738ff21e1 \
  --counter-party-address https://dil-connector.material.dil.collab-cloud.eu/api/dsp \
  --transfer-type s3-copy
```

The script sends:

```text
POST <management-url>/v5/participants/<participant-context-id>/transferprocesses
```

with an EDC v5 `TransferRequest` containing `contractId`, `assetId`,
`counterPartyAddress`, `transferType`, and the configured DSP `profile`.
Use `--protocol dataspace-protocol-http` instead of `--profile` when the
connector is configured with a protocol rather than a transfer profile.

Optional dataplane metadata and callback addresses can be supplied as JSON:

```bash
python3 test_scripts/request_transfer.py \
  --config /tmp/catalog-request.json \
  --contract-id "$CONTRACT_ID" \
  --transfer-type s3-copy \
  --dataplane-metadata /tmp/dataplane-metadata.json \
  --callback-addresses /tmp/callback-addresses.json
```

For `s3-copy`, the provider's catalog identifies the source object, for
example `provider-minio:dil-data/demo.csv`. Provider credentials and source
configuration stay on the provider connector. The consumer must have a
compatible S3 dataplane and destination configuration; do not copy provider
credentials into the management request.

## Check a transfer

Use the transfer ID returned by `request_transfer.py`:

```bash
python3 test_scripts/check_transfer.py \
  --config /tmp/catalog-request.json \
  --transfer-id <transfer-process-id>
```

Poll until the process reaches a terminal state:

```bash
python3 test_scripts/check_transfer.py \
  --config /tmp/catalog-request.json \
  --transfer-id <transfer-process-id> \
  --watch --interval 5
```

The script calls:

```text
GET <management-url>/v5/participants/<participant-context-id>/transferprocesses/<transfer-process-id>/state
```

Inspect the GX control-plane and dataplane logs if the transfer remains in
`REQUESTED`, or if it enters `TERMINATED`.

## Troubleshooting

- `401 Missing Authorization header`: the gateway requires a bearer token and none was sent.
- `401 Request could not be authenticated`: the API key or bearer token is invalid, expired, or signed by an untrusted issuer.
- `403 Forbidden`: authentication succeeded, but the token is missing the required management scope, normally `management-api:catalog:read`, or has an incorrect audience.
- `404`: check the management URL, participant context ID, and that the connector exposes Management API v5.
- `5xx` or a timeout after a successful management request: inspect the EDC control-plane logs for the outbound DSP request to the remote URL.
