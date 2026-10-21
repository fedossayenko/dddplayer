#!/usr/bin/env bash
# Сборка статической libdovi (C-API crate dolby_vision) из официального репозитория quietvoid/dovi_tool
# для ABI Android. Результат: dovi/src/main/cpp/libdovi/<abi>/libdovi.a (в git не попадает).
# Запускается задачей :dovi:buildLibdovi, можно и вручную: ./build_libdovi.sh libdovi-3.3.2 arm64-v8a armeabi-v7a
set -euo pipefail

TAG="${1:?usage: build_libdovi.sh <tag> <abi>...}"
shift
ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || { echo "no ABIs given" >&2; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/build/dovi_tool-$TAG"
OUT="$HERE/src/main/cpp/libdovi"

command -v cargo >/dev/null || { echo "cargo not found: install Rust from https://rustup.rs" >&2; exit 1; }

if [ ! -d "$SRC" ]; then
  git clone --depth 1 --branch "$TAG" https://github.com/quietvoid/dovi_tool.git "$SRC"
fi

for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android ;;
    armeabi-v7a) TARGET=armv7-linux-androideabi ;;
    x86) TARGET=i686-linux-android ;;
    x86_64) TARGET=x86_64-linux-android ;;
    *) echo "unknown ABI: $ABI" >&2; exit 1 ;;
  esac
  if command -v rustup >/dev/null; then rustup target add "$TARGET"; fi
  # Статическая библиотека не линкуется, поэтому линкер NDK для Rust не нужен
  cargo rustc --release --lib --features capi --crate-type staticlib \
    --manifest-path "$SRC/dolby_vision/Cargo.toml" --target "$TARGET"
  mkdir -p "$OUT/$ABI"
  cp "$SRC/dolby_vision/target/$TARGET/release/libdolby_vision.a" "$OUT/$ABI/libdovi.a"
  echo "libdovi $TAG -> $OUT/$ABI/libdovi.a"
done
