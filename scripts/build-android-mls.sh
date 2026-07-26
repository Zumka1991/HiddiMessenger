#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the installed Android NDK directory}"

TASK_API=35
TASK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

build_target() {
  local rust_target="$1"
  local compiler_prefix="$2"
  local cargo_env_prefix="$3"
  local cc_env_suffix="$4"
  local linker="$TASK_TOOLCHAIN/${compiler_prefix}${TASK_API}-clang"

  if [[ ! -x "$linker" ]]; then
    echo "Android linker not found: $linker" >&2
    exit 1
  fi

  rustup target add "$rust_target"
  export "${cargo_env_prefix}_LINKER=$linker"
  # `libsqlite3-sys` compiles SQLite from source. Cargo's linker setting is not
  # inherited by its C build script, so give `cc-rs` the same NDK compiler.
  export "CC_${cc_env_suffix}=$linker"
  export "CXX_${cc_env_suffix}=$TASK_TOOLCHAIN/${compiler_prefix}${TASK_API}-clang++"
  export "AR_${cc_env_suffix}=$TASK_TOOLCHAIN/llvm-ar"
  cargo build --manifest-path group-mls-core/Cargo.toml --release --target "$rust_target"
}

build_target \
  aarch64-linux-android \
  aarch64-linux-android \
  CARGO_TARGET_AARCH64_LINUX_ANDROID \
  aarch64_linux_android
build_target \
  x86_64-linux-android \
  x86_64-linux-android \
  CARGO_TARGET_X86_64_LINUX_ANDROID \
  x86_64_linux_android
