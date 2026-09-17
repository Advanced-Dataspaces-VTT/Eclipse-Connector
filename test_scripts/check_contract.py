#!/usr/bin/env python3
"""Read the state of an EDC v5 contract negotiation."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from urllib.parse import quote

from edc_test_utils import add_common_arguments, load_config, management_settings, request_json


def main() -> int:
    parser = argparse.ArgumentParser(description="Check an EDC contract negotiation state")
    add_common_arguments(parser)
    parser.add_argument("--negotiation-id", required=True, help="Contract negotiation ID")
    parser.add_argument("--output", help="Write the response JSON to this file")
    args = parser.parse_args()
    config = load_config(args.config)
    management_url, participant_id, timeout, insecure, headers = management_settings(args, config)
    endpoint = f"{management_url}/v5/participants/{quote(participant_id, safe='')}/contractnegotiations/{quote(args.negotiation_id, safe='')}/state"
    try:
        status, response = request_json(endpoint, "GET", None, headers, timeout, insecure)
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
