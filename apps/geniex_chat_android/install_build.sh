#!/usr/bin/env bash
set -euo pipefail

SDKMANAGER="$(command -v sdkmanager || true)"
if [[ -z "$SDKMANAGER" ]]; then
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$SDK_ROOT" ]]; then
    echo "ANDROID_HOME or ANDROID_SDK_ROOT must be set." >&2
    exit 1
  fi
  SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
fi

if [[ ! -x "$SDKMANAGER" ]]; then
  echo "sdkmanager was not found." >&2
  exit 1
fi

yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" \
  'platform-tools' \
  'platforms;android-34' \
  'build-tools;35.0.0' \
  'ndk;27.3.13750724'
