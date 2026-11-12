#!/usr/bin/env bash
# Build the kiosk APK inside a container (docker or podman).
# Result: app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
cd "$(dirname "$0")"

RUNTIME=${DOCKER_BIN:-$(command -v docker || command -v podman)}

"$RUNTIME" build -t kiosk-android-builder .

# Run as container root: with rootless podman that maps to your host uid,
# so build outputs are owned by you. The Gradle cache lives in a named
# volume, so AGP dependencies are reused between runs. The debug signing
# key lives in the project itself (debug.keystore), so signatures are stable.
"$RUNTIME" run --rm \
    -e GRADLE_USER_HOME=/tmp/gradle-home \
    -e HOME=/tmp \
    -v kiosk-gradle-cache:/tmp/gradle-home \
    -v "$PWD":/project \
    kiosk-android-builder

echo
echo "APK:"
ls -l app/build/outputs/apk/debug/app-debug.apk
