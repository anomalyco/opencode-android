#!/usr/bin/env bash
# The PsychoScript runner agent widgets run on (anomalyco/psychoscript, branch `mobile`, crate
# rust/crates/psychoscript-mobile), pinned to REV, built into vendor/psychoscript/ (gitignored),
# which :feature:transcript links as its jniLibs:
#
#   scripts/psychoscript.sh                     fetch REV into .psychoscript/ (gitignored) and build
#   scripts/psychoscript.sh --prebuilt <dir>    use a build of REV made elsewhere (<dir>/jniLibs, kotlin, REVISION)
#   scripts/psychoscript.sh --host              also build it for this machine into vendor/psychoscript/host/, for the
#                                               JVM tests that run widget workloads (they read them from .psychoscript/
#                                               and skip without either); needs cargo
#
# Building needs the NDK (r28+; ANDROID_NDK_HOME, else the newest under $ANDROID_HOME/ndk),
# `cargo install cargo-ndk` and `rustup target add --toolchain 1.100.0 aarch64-linux-android
# x86_64-linux-android` (the toolchain psychoscript's rust/rust-toolchain.toml names).
# The generated Kotlin bindings are committed (src/main/kotlin/com/anomalyco/psychoscript), so builds
# without Rust compile; this script refreshes them. Without the libraries, widgets show "The widget
# runtime isn't available".
set -euo pipefail
REV=ed5eb1cf0cb3141292f295463ae99cf18b00cf98
cd "$(dirname "$0")/.."
out=vendor/psychoscript
bindings=feature/transcript/src/main/kotlin/com/anomalyco/psychoscript

if [[ "${1:-}" == "--host" ]]; then
  export PATH="$HOME/.cargo/bin:$PATH"
  [[ -d .psychoscript ]] || git clone https://github.com/anomalyco/psychoscript .psychoscript
  git -C .psychoscript fetch -q origin "$REV"
  git -C .psychoscript checkout -q --detach "$REV"
  target="$PWD/.psychoscript/rust/target/host"
  (cd .psychoscript/rust && CARGO_TARGET_DIR="$target" cargo rustc -q --release -p psychoscript-mobile --lib --crate-type cdylib)
  rm -rf "$out/host"
  mkdir -p "$out/host"
  cp "$target"/release/libpsychoscript_mobile.{dylib,so} "$out/host/" 2>/dev/null || true
  ls "$out"/host/libpsychoscript_mobile.* >/dev/null 2>&1 || { echo "No host library in $target/release" >&2; exit 1; }
  echo "anomalyco/psychoscript $REV" > "$out/host/REVISION"
  echo "psychoscript $REV for this machine in $out/host"
  exit 0
fi

if [[ "${1:-}" == "--prebuilt" ]]; then
  src="${2:?usage: scripts/psychoscript.sh --prebuilt <dir>}"
  grep -q "$REV" "$src/REVISION" || { echo "$src is $(cat "$src/REVISION" 2>/dev/null || echo unknown), not $REV" >&2; exit 1; }
  rm -rf "$out/jniLibs" "$out/kotlin" "$out/REVISION"
  mkdir -p "$out"
  cp -R "$src/jniLibs" "$src/kotlin" "$src/REVISION" "$out/"
  rm -f "$out"/jniLibs/*/libopencode_widget_ffi.so
  rm -rf "$out/kotlin/ai"
else
  if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    ANDROID_NDK_HOME="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -n 1)"
    export ANDROID_NDK_HOME
  fi
  [[ -d "$ANDROID_NDK_HOME" ]] || { echo "No NDK: set ANDROID_NDK_HOME (r28 or later)" >&2; exit 1; }
  export PATH="$HOME/.cargo/bin:$PATH"
  [[ -d .psychoscript ]] || git clone https://github.com/anomalyco/psychoscript .psychoscript
  git -C .psychoscript fetch -q origin "$REV"
  git -C .psychoscript checkout -q --detach "$REV"
  .psychoscript/rust/crates/psychoscript-mobile/scripts/build-android.sh "$PWD/$out"
fi

grep -q "$REV" "$out/REVISION" || { echo "Built $(cat "$out/REVISION"), not $REV" >&2; exit 1; }
for f in psychoscript_mobile.kt PsychoScriptGpu.kt; do
  if [[ -f "$out/kotlin/com/anomalyco/psychoscript/$f" ]] && ! cmp -s "$out/kotlin/com/anomalyco/psychoscript/$f" "$bindings/$f"; then
    mkdir -p "$bindings"
    cp "$out/kotlin/com/anomalyco/psychoscript/$f" "$bindings/"
    echo "Updated the committed bindings ($f) in $bindings: commit them with the REV bump."
  fi
done
echo "psychoscript $(cat "$out/REVISION")"
ls -l "$out"/jniLibs/*/
