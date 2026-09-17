#!/usr/bin/env python3
"""Retrieve a Keycloak OAuth2 access token using client credentials."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def load_config(path: str) -> dict:
    try:
        with Path(path).open(encoding="utf-8") as stream:
            config = json.load(stream)
    except OSError as exc:
        raise SystemExit(f"Cannot read Keycloak config '{path}': {exc}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Keycloak config '{path}' is not valid JSON: {exc}") from exc
    if not isinstance(config, dict):
        raise SystemExit("Keycloak config must contain a JSON object")
    return config


def main() -> int:
    parser = argparse.ArgumentParser(description="Get a Keycloak OAuth2 access token")
    parser.add_argument(
        "--config",
        default="keycloak.json",
        help="Keycloak JSON config file (default: keycloak.json)",
    )
    parser.add_argument("--json", action="store_true", help="Print the complete token response")
    parser.add_argument("--output", help="Write the access token to a file")
    parser.add_argument("--insecure", action="store_true", help="Disable TLS certificate verification")
    args = parser.parse_args()

    config = load_config(args.config)
    base_url = str(config.get("base_url", "")).rstrip("/")
    realm = str(config.get("realm", "")).strip()
    client_id = str(config.get("client_id", "")).strip()
    client_secret = os.getenv("KEYCLOAK_CLIENT_SECRET", config.get("client_secret", ""))
    scope = str(config.get("scope", "openid management-api:catalog:read")).strip()

    missing = [
        name
        for name, value in (
            ("base_url", base_url),
            ("realm", realm),
            ("client_id", client_id),
            ("client_secret", client_secret),
        )
        if not value
    ]
    if missing:
        raise SystemExit(f"Missing Keycloak config value(s): {', '.join(missing)}")

    token_url = f"{base_url}/realms/{realm}/protocol/openid-connect/token"
    request = Request(
        token_url,
        data=urlencode(
            {
                "grant_type": "client_credentials",
                "client_id": client_id,
                "client_secret": client_secret,
                "scope": scope,
            }
        ).encode("utf-8"),
        headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
        method="POST",
    )

    import ssl

    context = ssl._create_unverified_context() if args.insecure else None
    try:
        with urlopen(request, timeout=float(config.get("timeout_seconds", 30)), context=context) as response:
            response_body = response.read().decode("utf-8")
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        print(f"Keycloak token request failed with HTTP {exc.code}: {body}", file=sys.stderr)
        return 1
    except (URLError, TimeoutError) as exc:
        print(f"Keycloak token request failed: {exc}", file=sys.stderr)
        return 2

    try:
        token_response = json.loads(response_body)
        access_token = token_response["access_token"]
    except (json.JSONDecodeError, KeyError, TypeError) as exc:
        print(f"Keycloak returned an unexpected response: {response_body}", file=sys.stderr)
        return 1

    if args.json:
        output = json.dumps(token_response, indent=2)
    else:
        output = access_token
    if args.output:
        Path(args.output).write_text(output + "\n", encoding="utf-8")
    else:
        print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
