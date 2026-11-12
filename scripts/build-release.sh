#!/usr/bin/env bash
# Build a signed release APK locally.
#
# Usage:
#   ./scripts/build-release.sh                     # build :app only
#   ./scripts/build-release.sh --all               # also build :probe (acceptance probe)
#   ANDROID_SIGNING_ENV=/path/to/env ./scripts/build-release.sh
#
# Signing info comes from a shell snippet file, by default ~/.config/zsh/secret/android-signing.zsh,
# which only exports four variables and is never committed:
#   RELEASE_STORE_PASSWORD / RELEASE_KEY_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEYSTORE_BASE64
set -euo pipefail

cd "$(dirname "$0")/.."

SIGNING_ENV="${ANDROID_SIGNING_ENV:-$HOME/.config/zsh/secret/android-signing.zsh}"
if [[ -f "$SIGNING_ENV" ]]; then
    # shellcheck disable=SC1090
    source "$SIGNING_ENV"
    export RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEYSTORE_BASE64
    echo "signing: loaded $SIGNING_ENV"
else
    echo "signing: $SIGNING_ENV not found -> release APK will be UNSIGNED" >&2
fi

TASKS=(:app:assembleRelease)
if [[ "${1:-}" == "--all" ]]; then
    TASKS+=(:probe:assembleRelease)
fi

exec ./gradlew "${TASKS[@]}"
