#!/usr/bin/env python3
"""Initiate an EDC v5 transfer process for an agreed contract."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from urllib.parse import quote

from edc_test_utils import DEFAULT_PROFILE, MANAGEMENT_CONTEXT, add_common_arguments, choose, load_config, management_settings, request_json


def read_json_file(path: str):
    try:
        with Path(path).open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Cannot read JSON file '{path}': {exc}") from exc
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="Initiate an EDC v5 transfer process")
    add_common_arguments(parser)
    parser.add_argument("--contract-id", help="Agreed contract ID")
    parser.add_argument("--negotiation-id", help="Negotiation ID; used to look up contractId when --contract-id is omitted")
    parser.add_argument("--asset-id", help="Asset/dataset ID")
    parser.add_argument("--counter-party-address", help="Provider DSP endpoint")
    parser.add_argument("--transfer-type", help="Transfer type, for example s3-copy or HttpData-PULL")
    parser.add_argument("--profile", help=f"Registered EDC transfer profile (default: {DEFAULT_PROFILE})")
    parser.add_argument("--protocol", help="Registered protocol; use instead of --profile")
    parser.add_argument("--dataplane-metadata", help="JSON file containing dataplaneMetadata")
    parser.add_argument("--callback-addresses", help="JSON file containing callbackAddresses array")
    parser.add_argument("--output", help="Write the transfer response to this file")
    args = parser.parse_args()

    config = load_config(args.config)
    management_url, participant_id, timeout, insecure, headers = management_settings(args, config)
    participant_path = quote(participant_id, safe="")

    contract_id = choose(args.contract_id, config, "contract_id")
    negotiation_id = choose(args.negotiation_id, config, "negotiation_id")
    if not contract_id and negotiation_id:
        lookup_endpoint = (
            f"{management_url}/v5/participants/{participant_path}/contractnegotiations/"
            f"{quote(str(negotiation_id), safe='')}"
        )
        try:
            _, negotiation = request_json(lookup_endpoint, "GET", None, headers, timeout, insecure)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return 1
        if isinstance(negotiation, dict):
            contract_id = (
                negotiation.get("contractAgreementId")
                or negotiation.get("contractId")
                or negotiation.get("agreementId")
            )
    if not contract_id:
        raise SystemExit("contract_id is required, or provide negotiation_id for an agreement lookup")

    counter_party_address = choose(args.counter_party_address, config, "counter_party_address")
    transfer_type = choose(args.transfer_type, config, "transfer_type", "s3-copy")
    profile = choose(args.profile, config, "profile")
    protocol = choose(args.protocol, config, "protocol")
    asset_id = choose(args.asset_id, config, "asset_id")
    if not counter_party_address:
        counter_party_address = config.get("remote_url")
    if not counter_party_address:
        raise SystemExit("counter_party_address is required")
    if not profile and not protocol:
        profile = DEFAULT_PROFILE
    if profile and protocol:
        raise SystemExit("Choose only one of profile or protocol")

    body = {
        "@context": [MANAGEMENT_CONTEXT],
        "@type": "TransferRequest",
        "counterPartyAddress": counter_party_address,
        "contractId": contract_id,
        "transferType": transfer_type,
    }
    if profile:
        body["profile"] = profile
    elif protocol:
        body["protocol"] = protocol
    if asset_id:
        body["assetId"] = asset_id

    dataplane_metadata = args.dataplane_metadata or config.get("dataplane_metadata_file")
    if dataplane_metadata:
        value = read_json_file(dataplane_metadata)
        if not isinstance(value, dict):
            raise SystemExit("dataplane metadata JSON must contain an object")
        body["dataplaneMetadata"] = value
    callback_addresses = args.callback_addresses or config.get("callback_addresses_file")
    if callback_addresses:
        value = read_json_file(callback_addresses)
        value = value.get("callbackAddresses", value) if isinstance(value, dict) else value
        if not isinstance(value, list):
            raise SystemExit("callback addresses JSON must contain an array")
        body["callbackAddresses"] = value

    endpoint = f"{management_url}/v5/participants/{participant_path}/transferprocesses"
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
