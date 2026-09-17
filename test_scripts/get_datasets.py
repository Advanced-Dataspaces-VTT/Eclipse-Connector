#!/usr/bin/env python3
"""Fetch and list datasets from a remote DSP catalog."""

from __future__ import annotations

import argparse
import json
import sys

from edc_test_utils import DEFAULT_CONTEXT, DEFAULT_PROFILE, catalog_request, choose, load_config, management_settings, request_json


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch datasets from a remote DSP catalog")
    parser.add_argument("--remote-url", help="Remote connector DSP URL")
    parser.add_argument("--remote-did", help="Remote participant DID")
    parser.add_argument("--profile", help=f"Registered profile (default: {DEFAULT_PROFILE})")
    parser.add_argument("--context", dest="jsonld_context", help="JSON-LD context URL")
    parser.add_argument("--additional-scope", action="append", dest="additional_scopes")
    parser.add_argument("--output", help="Write the full catalog to this file")
    from edc_test_utils import add_common_arguments
    add_common_arguments(parser)
    args = parser.parse_args()
    config = load_config(args.config)
    settings = management_settings(args, config)
    remote_url = choose(args.remote_url, config, "remote_url")
    remote_did = choose(args.remote_did, config, "remote_did")
    profile = choose(args.profile, config, "profile", DEFAULT_PROFILE)
    context = choose(args.jsonld_context, config, "context", DEFAULT_CONTEXT)
    scopes = choose(args.additional_scopes, config, "additional_scopes", [])
    if not remote_url or not remote_did:
        raise SystemExit("remote_url and remote_did are required")
    try:
        _, status, catalog = catalog_request(config, remote_url, remote_did, profile, context, scopes, settings)
        datasets = catalog.get("dataset", []) if isinstance(catalog, dict) else []
        result = {"datasets": datasets, "count": len(datasets)}
        output = json.dumps(result, indent=2)
        if args.output:
            with open(args.output, "w", encoding="utf-8") as stream:
                stream.write(json.dumps(catalog, indent=2) + "\n")
        print(output)
        print(f"HTTP {status}; datasets: {len(datasets)}", file=sys.stderr)
        return 0
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
