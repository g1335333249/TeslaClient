#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
output_dir=${1:-"$repo_root/dist/ios"}
mkdir -p "$output_dir"
output_dir=$(cd "$output_dir" && pwd)

metadata=$(python3 "$repo_root/scripts/ios-version.py" "$repo_root/ios/project.yml" "${EXPECTED_VERSION:-}")
version=$(printf '%s\n' "$metadata" | sed -n 's/^version=//p')
build=$(printf '%s\n' "$metadata" | sed -n 's/^build=//p')
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/voltmate-ios-ipa.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT

(
  cd "$repo_root/ios"
  xcodegen generate
  xcodebuild -quiet -project VoltMate.xcodeproj -scheme VoltMate -configuration Release \
    -sdk iphoneos -destination 'generic/platform=iOS' \
    -archivePath "$work_dir/VoltMate.xcarchive" \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' \
    DEVELOPMENT_TEAM='' archive
)

app="$work_dir/VoltMate.xcarchive/Products/Applications/VoltMate.app"
if [[ ! -f "$app/Info.plist" ]]; then
  echo 'Xcode archive does not contain VoltMate.app' >&2
  exit 1
fi
if codesign -dv "$app" >/dev/null 2>&1; then
  echo 'Refusing to publish a signed app as an unsigned IPA' >&2
  exit 1
fi

mkdir -p "$work_dir/staging/Payload"
ditto "$app" "$work_dir/staging/Payload/VoltMate.app"
(
  cd "$work_dir/staging"
  zip -qry "$work_dir/VoltMate.ipa" Payload
)
unzip -tq "$work_dir/VoltMate.ipa"
python3 - "$work_dir/VoltMate.ipa" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    names = archive.namelist()
    if "Payload/VoltMate.app/Info.plist" not in names:
        raise SystemExit("IPA does not contain the application bundle")
    if any("/_CodeSignature/" in name for name in names):
        raise SystemExit("IPA unexpectedly contains a code signature")
PY

ipa="$output_dir/VoltMate-${version}+${build}-unsigned.ipa"
mv "$work_dir/VoltMate.ipa" "$ipa"
echo "Unsigned IPA: $ipa"
