#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
keystore_path="${MH_UPLOAD_STORE_FILE:-$HOME/.mobile-harness/mobile-harness-code-upload.jks}"
key_alias="${MH_UPLOAD_KEY_ALIAS:-mobile-harness-upload}"
keychain_account="com.mobileharness.code"
keychain_service="Mobile Harness Code Upload Key"
version_code="${1:-1}"
version_name="${2:-1.0.0}"

if [[ ! -f "$keystore_path" ]]; then
  echo "Upload keystore not found: $keystore_path" >&2
  exit 1
fi

upload_secret="$(security find-generic-password -w -a "$keychain_account" -s "$keychain_service")"
trap 'unset upload_secret' EXIT

cd "$project_dir"
MH_UPLOAD_STORE_FILE="$keystore_path" \
MH_UPLOAD_STORE_PASSWORD="$upload_secret" \
MH_UPLOAD_KEY_ALIAS="$key_alias" \
MH_UPLOAD_KEY_PASSWORD="$upload_secret" \
./gradlew \
  -PplayBuild=true \
  -PappVersionCode="$version_code" \
  -PappVersionName="$version_name" \
  playReadinessCheck testOnlineDebugUnitTest lintOnlineDebug assembleRelease bundleRelease

online_apk="$project_dir/app/build/outputs/apk/online/release/app-online-release.apk"
offline_apk="$project_dir/app/build/outputs/apk/offline/release/app-offline-release.apk"

echo "Signed online bundle: $project_dir/app/build/outputs/bundle/onlineRelease/app-online-release.aab"
echo "Signed offline bundle: $project_dir/app/build/outputs/bundle/offlineRelease/app-offline-release.aab"
echo "Signed online APK: $online_apk"
echo "Signed offline APK: $offline_apk"

# --- Mandatory signature verification -----------------------------------------------------
# The release process refuses to publish an APK whose signature schemes cannot
# be verified by apksigner. This catches broken V1/V2/V3 signature blocks before
# they reach a device (see issue #102: installs rejected on Android 16).
apksigner_bin="${APKSIGNER_BIN:-}"
if [[ -z "$apksigner_bin" ]]; then
  for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK:-}"; do
    if [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]]; then
      apksigner_bin="$(find "$sdk_root/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -n 1)"
      [[ -n "$apksigner_bin" ]] && break
    fi
  done
fi
if [[ -z "$apksigner_bin" ]]; then
  apksigner_bin="$(command -v apksigner 2>/dev/null || true)"
fi
if [[ -z "$apksigner_bin" ]]; then
  echo "apksigner not found. Set APKSIGNER_BIN or install Android build-tools." >&2
  exit 1
fi

verify_apk() {
  local apk="$1"
  echo ""
  echo "Verifying signatures: $apk"
  "$apksigner_bin" verify --verbose --print-certs "$apk"
  echo "SHA-256: $(shasum -a 256 "$apk" 2>/dev/null | awk '{print $1}' || sha256sum "$apk" | awk '{print $1}')  $(basename "$apk")"
}

verify_apk "$online_apk"
verify_apk "$offline_apk"
