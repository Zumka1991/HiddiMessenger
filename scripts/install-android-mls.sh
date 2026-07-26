#!/usr/bin/env bash
set -euo pipefail

TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$TASK_ROOT/scripts/build-android-mls.sh"

install_library() {
  local rust_target="$1"
  local android_abi="$2"
  local source="$TASK_ROOT/group-mls-core/target/$rust_target/release/libhiddi_group_mls_core.so"
  local destination="$TASK_ROOT/android/app/src/main/jniLibs/$android_abi/libhiddi_group_mls_core.so"
  install -D -m 0644 "$source" "$destination"
  echo "Installed $destination"
}

install_library aarch64-linux-android arm64-v8a
install_library x86_64-linux-android x86_64
