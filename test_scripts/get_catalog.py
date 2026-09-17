#!/usr/bin/env python3
"""Request a remote DSP catalog through an EDC v5 Management API."""

from __future__ import annotations

import argparse
import json
import os
import ssl
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


DEFAULT_CONTEXT = "https://w3id.org/dspace/2025/1/context.jsonld"
MANAGEMENT_CONTEXT = "https://w3id.org/edc/connector/management/v2"
DEFAULT_PROFILE = "http-dsp-profile-2025-1"


def load_config(path: str | None) -> dict:
    if not path:
        return {}
    try:
        with Path(path).open(encoding="utf-8") as stream:
            config = json.load(stream)
    except OSError as exc:
        raise SystemExit(f"Cannot read config file '{path}': {exc}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Config file '{path}' is not valid JSON: {exc}") from exc
    if not isinstance(config, dict):
        raise SystemExit("The config file must contain a JSON object")
    return config


def choose(cli_value, config: dict, key: str, default=None):
    return cli_value if cli_value is not None else config.get(key, default)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Request a remote catalog using an EDC v5 Management API"
    )
    parser.add_argument("--config", help="JSON file containing request settings")
    parser.add_argument("--management-url", help="EDC Management API base URL")
    parser.add_argument(
        "--participant-context-id",
        help="Local EDC participant context ID used in the v5 URL; config fallback is participant_did",
    )
    parser.add_argument(
        "--remote-url",
        help="Remote connector DSP URL, for example https://provider.example/api/dsp",
    )
    parser.add_argument("--remote-did", help="Remote participant DID")
    parser.add_argument("--api-key", help="Static API key sent as X-Api-Key")
    parser.add_argument(
        "--bearer-token",
        help="OAuth2 access token sent as Authorization: Bearer <token>",
    )
    parser.add_argument(
        "--authorization-header",
        help="Complete custom Authorization header value; mutually exclusive with --api-key/--bearer-token",
    )
    parser.add_argument("--profile", default=None, help=f"Registered EDC profile ID (default: {DEFAULT_PROFILE})")
    parser.add_argument("--context", dest="jsonld_context", default=None, help="JSON-LD context URL")
    parser.add_argument(
        "--additional-scope",
        action="append",
        dest="additional_scopes",
        help="DCP credential scope to request from the remote connector; repeatable",
    )
    parser.add_argument("--timeout", type=float, default=None, help="HTTP timeout in seconds (default: 30)")
    parser.add_argument("--insecure", action="store_true", help="Disable TLS certificate verification")
    parser.add_argument("--output", help="Write the response JSON to this file")
    return parser


def request_catalog(args: argparse.Namespace) -> int:
    config = load_config(args.config)
    auth_config = config.get("authorization", {})
    if not isinstance(auth_config, dict):
        raise SystemExit("authorization in the config file must be a JSON object")

    management_url = choose(args.management_url, config, "management_url")
    participant_context_id = choose(
        args.participant_context_id,
        config,
        "participant_context_id",
        config.get("participant_did"),
    )
    remote_url = choose(args.remote_url, config, "remote_url")
    remote_did = choose(args.remote_did, config, "remote_did")
    profile = choose(args.profile, config, "profile", DEFAULT_PROFILE)
    jsonld_context = choose(args.jsonld_context, config, "context", DEFAULT_CONTEXT)
    additional_scopes = choose(args.additional_scopes, config, "additional_scopes", [])
    timeout = choose(args.timeout, config, "timeout_seconds", 30)
    insecure = args.insecure or bool(config.get("insecure_tls", False))

    if not management_url or not participant_context_id or not remote_url or not remote_did:
        raise SystemExit(
            "management_url, participant_context_id, remote_url, and remote_did are required "
            "(use command-line options or --config)"
        )

    api_key = choose(args.api_key, auth_config, "api_key")
    bearer_token = choose(args.bearer_token, auth_config, "bearer_token")
    authorization_header = choose(args.authorization_header, auth_config, "header")
    selected_auth = sum(value is not None for value in (api_key, bearer_token, authorization_header))
    if selected_auth > 1:
        raise SystemExit("Choose only one of api_key, bearer_token, or authorization header")

    management_url = management_url.rstrip("/")
    endpoint = (
        f"{management_url}/v5/participants/"
        f"{quote(str(participant_context_id), safe='')}/catalog/request"
    )
    configured_contexts = jsonld_context if isinstance(jsonld_context, list) else [jsonld_context]
    contexts = [MANAGEMENT_CONTEXT] + [
        context for context in configured_contexts if context != MANAGEMENT_CONTEXT
    ]
    body = {
        "@context": contexts,
        "@type": "CatalogRequest",
        "profile": profile,
        "counterPartyId": remote_did,
        "counterPartyAddress": remote_url,
    }
    if additional_scopes:
        body["additionalScopes"] = additional_scopes
    request = Request(
        endpoint,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    if api_key is not None:
        request.add_header("X-Api-Key", api_key)
    elif bearer_token is not None:
        request.add_header("Authorization", f"Bearer {bearer_token}")
    elif authorization_header is not None:
        request.add_header("Authorization", authorization_header)

    tls_context = ssl._create_unverified_context() if insecure else None
    try:
        with urlopen(request, timeout=float(timeout), context=tls_context) as response:
            raw = response.read()
            status = response.status
    except HTTPError as exc:
        raw = exc.read()
        status = exc.code
        print(f"HTTP {status} from {endpoint}", file=sys.stderr)
        try:
            print(json.dumps(json.loads(raw), indent=2), file=sys.stderr)
        except (UnicodeDecodeError, json.JSONDecodeError):
            print(raw.decode("utf-8", errors="replace"), file=sys.stderr)
        return 1
    except (URLError, TimeoutError) as exc:
        print(f"Request failed for {endpoint}: {exc}", file=sys.stderr)
        return 2

    text = raw.decode("utf-8", errors="replace")
    try:
        result = json.loads(text)
        output = json.dumps(result, indent=2)
    except json.JSONDecodeError:
        output = text
    if args.output:
        Path(args.output).write_text(output + "\n", encoding="utf-8")
    else:
        print(output)
    print(f"HTTP {status}", file=sys.stderr)
    return 0 if 200 <= status < 300 else 1


def main() -> int:
    args = build_parser().parse_args()
    return request_catalog(args)


if __name__ == "__main__":
    sys.exit(main())
