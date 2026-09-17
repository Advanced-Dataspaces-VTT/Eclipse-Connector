#!/usr/bin/env python3
"""Initiate an EDC v5 contract negotiation from a catalog dataset."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from urllib.parse import quote

from edc_test_utils import DEFAULT_PROFILE, MANAGEMENT_CONTEXT, add_common_arguments, choose, load_config, management_settings, request_json


def main() -> int:
    parser = argparse.ArgumentParser(description="Initiate a contract negotiation for a catalog dataset")
    add_common_arguments(parser)
    parser.add_argument("--catalog", required=True, help="Catalog JSON file produced by get_catalog.py")
    parser.add_argument("--dataset-id", required=True, help="Dataset @id to negotiate")
    parser.add_argument("--remote-url", help="Remote connector DSP URL; defaults to the catalog service endpoint")
    parser.add_argument("--profile", help=f"Registered profile (default: {DEFAULT_PROFILE})")
    parser.add_argument("--output", help="Write the negotiation response to this file")
    args = parser.parse_args()
    config = load_config(args.config)
    management_url, participant_id, timeout, insecure, headers = management_settings(args, config)
    try:
        with Path(args.catalog).open(encoding="utf-8") as stream:
            catalog = json.load(stream)
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Cannot read catalog '{args.catalog}': {exc}") from exc
    datasets = catalog.get("dataset", [])
    dataset = next((item for item in datasets if item.get("@id") == args.dataset_id), None)
    if dataset is None:
        raise SystemExit(f"Dataset '{args.dataset_id}' was not found in {args.catalog}")
    policies = dataset.get("hasPolicy", [])
    if not policies:
        raise SystemExit(f"Dataset '{args.dataset_id}' has no hasPolicy offer")
    remote_url = choose(args.remote_url, config, "remote_url")
    if not remote_url:
        services = catalog.get("service", [])
        remote_url = services[0].get("endpointURL") if services else None
    profile = choose(args.profile, config, "profile", DEFAULT_PROFILE)
    if not remote_url:
        raise SystemExit("remote_url is required or must be present in the catalog service")
    policy = dict(policies[0])
    # Dataset-level catalog offers may omit target; the management API requires it.
    policy.setdefault("target", args.dataset_id)
    body = {
        "@context": [MANAGEMENT_CONTEXT],
        "@type": "ContractRequest",
        "counterPartyAddress": remote_url,
        "profile": profile,
        "policy": policy,
    }
    endpoint = f"{management_url}/v5/participants/{quote(participant_id, safe='')}/contractnegotiations"
    try:
        status, response = request_json(endpoint, "POST", body, headers, timeout, insecure)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    output = json.dumps(response, indent=2)
    if args.output:
        Path(args.output).write_text(output + "\n", encoding="utf-8")
    print(output)
    print(f"HTTP {status}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
