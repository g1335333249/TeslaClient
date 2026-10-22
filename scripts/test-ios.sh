#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../ios"
xcodegen generate
device_id=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(x["udid"] for xs in d["devices"].values() for x in xs if x["name"].startswith("iPhone") and x.get("isAvailable", True)))')
xcodebuild -project VoltMate.xcodeproj -scheme VoltMate -destination "platform=iOS Simulator,id=$device_id" CODE_SIGNING_ALLOWED=NO test
