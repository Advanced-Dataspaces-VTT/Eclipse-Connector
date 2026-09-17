#!/usr/bin/env python3
"""Shared helpers for the EDC management API test scripts."""

from __future__ import annotations

import json
import ssl
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


MANAGEMENT_CONTEXT = "https://w3id.org/edc/connector/management/v2"
DEFAULT_CONTEXT = "https://w3id.org/dspace/2025/1/context.jsonld"
DEFAULT_PROFILE = "http-dsp-profile-2025-1"


def load_config(path: str | None) -> dict:
    if not path:
        return {}
    try:
        with Path(path).open(encoding="utf-8") as stream:
            value = json.load(stream)
    except OSError as exc:
        raise SystemExit(f"Cannot read config file '{path}': {exc}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Config file '{path}' is not valid JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise SystemExit("The config file must contain a JSON object")
    return value


def choose(cli_value, config: dict, key: str, default=None):
    return cli_value if cli_value is not None else config.get(key, default)


def auth_headers(config: dict, api_key=None, bearer_token=None, authorization_header=None) -> dict:
    auth = config.get("authorization", {})
    if not isinstance(auth, dict):
        raise SystemExit("authorization in the config file must be a JSON object")
    values = (
        api_key if api_key is not None else auth.get("api_key"),
        bearer_token if bearer_token is not None else auth.get("bearer_token"),
        authorization_header if authorization_header is not None else auth.get("header"),
    )
    if sum(value is not None for value in values) > 1:
        raise SystemExit("Choose only one of api_key, bearer_token, or authorization header")
    headers = {"Accept": "application/json"}
    if values[0] is not None:
        headers["X-Api-Key"] = values[0]
    elif values[1] is not None:
        headers["Authorization"] = f"Bearer {values[1]}"
    elif values[2] is not None:
        headers["Authorization"] = values[2]
    return headers


def request_json(url: str, method: str, body: dict | None, headers: dict, timeout: float, insecure: bool):
    request_headers = dict(headers)
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    request = Request(
        url,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        headers=request_headers,
        method=method,
    )
    context = ssl._create_unverified_context() if insecure else None
    try:
        with urlopen(request, timeout=timeout, context=context) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            detail = json.loads(raw)
        except json.JSONDecodeError:
            detail = raw
        raise RuntimeError(f"HTTP {exc.code} from {url}: {json.dumps(detail)}") from exc
    except (URLError, TimeoutError) as exc:
        raise RuntimeError(f"Request failed for {url}: {exc}") from exc


def management_settings(args, config: dict):
    management_url = choose(args.management_url, config, "management_url")
    participant_context_id = choose(args.participant_context_id, config, "participant_context_id", config.get("participant_did"))
    timeout = float(choose(args.timeout, config, "timeout_seconds", 30))
    insecure = args.insecure or bool(config.get("insecure_tls", False))
    if not management_url or not participant_context_id:
        raise SystemExit("management_url and participant_context_id are required")
    headers = auth_headers(config, args.api_key, args.bearer_token, args.authorization_header)
    return management_url.rstrip("/"), str(participant_context_id), timeout, insecure, headers


def add_common_arguments(parser):
    parser.add_argument("--config", help="JSON file containing request settings")
    parser.add_argument("--management-url", help="EDC Management API base URL")
    parser.add_argument("--participant-context-id", help="Local participant context ID")
    parser.add_argument("--api-key", help="Static API key sent as X-Api-Key")
    parser.add_argument("--bearer-token", help="OAuth2 access token")
    parser.add_argument("--authorization-header", help="Complete Authorization header value")
    parser.add_argument("--timeout", type=float, help="HTTP timeout in seconds")
    parser.add_argument("--insecure", action="store_true", help="Disable TLS certificate verification")


def catalog_request(config: dict, remote_url: str, remote_did: str, profile: str, context, additional_scopes, settings):
    management_url, participant_context_id, timeout, insecure, headers = settings
    contexts = context if isinstance(context, list) else [context]
    body = {
        "@context": [MANAGEMENT_CONTEXT] + [item for item in contexts if item != MANAGEMENT_CONTEXT],
        "@type": "CatalogRequest",
        "profile": profile,
        "counterPartyId": remote_did,
        "counterPartyAddress": remote_url,
    }
    if additional_scopes:
        body["additionalScopes"] = additional_scopes
    endpoint = f"{management_url}/v5/participants/{quote(participant_context_id, safe='')}/catalog/request"
    status, response = request_json(endpoint, "POST", body, headers, timeout, insecure)
    return endpoint, status, response
