#!/usr/bin/env python3
"""Read or poll an EDC v5 transfer-process state."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from urllib.parse import quote

from edc_test_utils import add_common_arguments, load_config, management_settings, request_json


def main() -> int:
    parser = argparse.ArgumentParser(description="Check an EDC v5 transfer-process state")
    add_common_arguments(parser)
    parser.add_argument("--transfer-id", required=True, help="Transfer process ID")
    parser.add_argument("--watch", action="store_true", help="Poll until COMPLETED or a terminal failure state")
    parser.add_argument("--interval", type=float, default=5.0, help="Polling interval in seconds (default: 5)")
    parser.add_argument("--max-attempts", type=int, default=60, help="Maximum polling attempts (default: 60)")
    parser.add_argument("--output", help="Write the latest response JSON to this file")
    args = parser.parse_args()

    config = load_config(args.config)
    management_url, participant_id, timeout, insecure, headers = management_settings(args, config)
    endpoint_base = (
        f"{management_url}/v5/participants/{quote(participant_id, safe='')}/transferprocesses/"
        f"{quote(args.transfer_id, safe='')}"
    )
    endpoint = f"{endpoint_base}/state"
    attempts = args.max_attempts if args.watch else 1
    response = None
    status = 0
    for attempt in range(attempts):
        try:
            status, response = request_json(endpoint, "GET", None, headers, timeout, insecure)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return 1
        if not args.watch:
            break
        state = response.get("state") if isinstance(response, dict) else None
        print(f"attempt {attempt + 1}: state={state}", file=sys.stderr)
        if state in {"COMPLETED", "TERMINATED", "ERROR", "DEPROVISIONED"}:
            break
        if attempt + 1 < attempts:
            time.sleep(args.interval)

    output = json.dumps(response, indent=2)
    if args.output:
        Path(args.output).write_text(output + "\n", encoding="utf-8")
    print(output)
    print(f"HTTP {status}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
