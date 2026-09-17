#!/usr/bin/env python3
"""Inspect and validate the claims of a JWT access token."""

from __future__ import annotations

import argparse
import base64
import json
import sys
import time


def decode_part(value: str) -> dict:
    try:
        padded = value + "=" * (-len(value) % 4)
        decoded = base64.urlsafe_b64decode(padded).decode("utf-8")
        claims = json.loads(decoded)
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid JWT payload: {exc}") from exc
    if not isinstance(claims, dict):
        raise ValueError("JWT payload is not a JSON object")
    return claims


def as_list(value) -> list[str]:
    if isinstance(value, list):
        return [str(item) for item in value]
    if isinstance(value, str):
        return value.split()
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description="Inspect and validate an EDC JWT access token")
    parser.add_argument("token", help="JWT access token, without the 'Bearer' prefix")
    parser.add_argument(
        "--issuer",
        default="https://dil.collab-cloud.eu/auth/realms/gx-participant1",
        help="Expected issuer",
    )
    parser.add_argument("--audience", default="connector-api", help="Expected audience")
    parser.add_argument(
        "--scope",
        action="append",
        dest="required_scopes",
        default=["management-api:catalog:read"],
        help="Required scope; may be specified more than once",
    )
    args = parser.parse_args()

    token = args.token.removeprefix("Bearer ").strip()
    parts = token.split(".")
    if len(parts) != 3:
        print("ERROR: token is not a three-part JWT", file=sys.stderr)
        return 1

    try:
        header = decode_part(parts[0])
        claims = decode_part(parts[1])
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    now = int(time.time())
    scopes = as_list(claims.get("scope"))
    audiences = as_list(claims.get("aud"))
    print(json.dumps({
        "header": header,
        "claims": {
            key: claims.get(key)
            for key in ("iss", "sub", "aud", "scope", "iat", "nbf", "exp", "azp")
            if key in claims
        },
    }, indent=2))

    errors: list[str] = []
    if args.issuer and claims.get("iss") != args.issuer:
        errors.append(f"issuer mismatch: got {claims.get('iss')!r}, expected {args.issuer!r}")
    if args.audience and args.audience not in audiences:
        errors.append(f"audience missing: {args.audience!r} not in {audiences!r}")
    for required_scope in args.required_scopes:
        if required_scope not in scopes:
            errors.append(f"scope missing: {required_scope!r}")
    if not isinstance(claims.get("exp"), (int, float)):
        errors.append("expiration claim 'exp' is missing or invalid")
    elif claims["exp"] <= now:
        errors.append("token is expired")

    if errors:
        print("\nINVALID for the configured EDC checks:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("\nToken claims match the configured issuer, audience, scopes, and expiry.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
