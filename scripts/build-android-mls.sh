#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the installed Android NDK directory}"

TASK_API=35
TASK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
TASK_LINKER="$TASK_TOOLCHAIN/aarch64-linux-android${TASK_API}-clang"

if [[ ! -x "$TASK_LINKER" ]]; then
  echo "Android arm64 linker not found: $TASK_LINKER" >&2
  exit 1
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TASK_LINKER"
# `libsqlite3-sys` compiles SQLite from source. Cargo's linker setting is not
# inherited by its C build script, so give `cc-rs` the same NDK compiler.
export CC_aarch64_linux_android="$TASK_LINKER"
export CXX_aarch64_linux_android="$TASK_TOOLCHAIN/aarch64-linux-android${TASK_API}-clang++"
export AR_aarch64_linux_android="$TASK_TOOLCHAIN/llvm-ar"
exec cargo build --manifest-path group-mls-core/Cargo.toml --release --target aarch64-linux-android
