#!/usr/bin/env bash
# Builds reviewable release artifacts. Never creates keys or falls back to debug signing.
set -euo pipefail
cd "$(dirname "$0")/.."

for variable in KEYSTORE_PATH STORE_PASSWORD KEY_PASSWORD; do
  if [[ -z "${!variable:-}" ]]; then
    printf 'Release signing requires %s. No release was built.\n' "$variable" >&2
    exit 2
  fi
done
if [[ ! -f "$KEYSTORE_PATH" ]]; then
  printf 'The configured release keystore does not exist. No release was built.\n' >&2
  exit 2
fi
command -v gradle >/dev/null || { printf 'Gradle is required.\n' >&2; exit 2; }
command -v jarsigner >/dev/null || { printf 'JDK jarsigner is required.\n' >&2; exit 2; }
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" || ! -d "$sdk_root/build-tools" ]]; then
  printf 'An Android SDK with build-tools is required.\n' >&2
  exit 2
fi
apksigner="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -name apksigner -type f | sort -V | tail -n 1)"
if [[ -z "$apksigner" ]]; then printf 'Android apksigner is required.\n' >&2; exit 2; fi

gradle :app:bundleRelease :app:assembleRelease "-PROUTEPASS_API_BASE_URL=${ROUTEPASS_API_BASE_URL:-https://passo.live/}"
bundle="app/build/outputs/bundle/release/app-release.aab"
apk="app/build/outputs/apk/release/app-release.apk"
[[ -s "$bundle" && -s "$apk" ]] || { printf 'Release outputs are missing.\n' >&2; exit 1; }
verification="$(jarsigner -J-Duser.language=en -verify "$bundle")"
if ! printf '%s\n' "$verification" | grep -Fq 'jar verified.'; then
  printf 'The app bundle signature could not be verified.\n' >&2
  exit 1
fi
"$apksigner" verify "$apk"
sha256sum "$bundle" "$apk"
printf 'Signed release bundle and APK verified. Play Console upload remains a separate step.\n'
