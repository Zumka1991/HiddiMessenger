#!/usr/bin/env bash
set -euo pipefail

TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$TASK_ROOT/scripts/build-android-mls.sh"

TASK_SOURCE="$TASK_ROOT/group-mls-core/target/aarch64-linux-android/release/libhiddi_group_mls_core.so"
TASK_DESTINATION="$TASK_ROOT/android/app/src/main/jniLibs/arm64-v8a/libhiddi_group_mls_core.so"
install -D -m 0644 "$TASK_SOURCE" "$TASK_DESTINATION"
echo "Installed $TASK_DESTINATION"
