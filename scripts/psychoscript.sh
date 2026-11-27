#!/usr/bin/env bash
# The widget runtime agent widgets run on: opencode's widget-mobile (anomalyco/opencode, Rust workspace
# packages/widgets-rs, crate widget-mobile: PsychoScript's psychoscript-mobile, linked by git rev, plus the widget
# parts), pinned to REV, built into vendor/psychoscript/ (gitignored), which :feature:transcript links as its jniLibs:
#
#   scripts/psychoscript.sh                     fetch REV into .widgets-rs/ (gitignored; a partial clone of the one
#                                               commit, checking out only packages/widgets-rs and packages/widget) and build
#   scripts/psychoscript.sh --prebuilt <dir>    use a build of REV made elsewhere (<dir>/jniLibs, kotlin, REVISION)
#   scripts/psychoscript.sh --host              also build it for this machine into vendor/psychoscript/host/, for the
#                                               JVM tests that run widget workloads (they read them from .widgets-rs/
#                                               and skip without either); needs cargo
#
# Building needs the NDK (r28+; ANDROID_NDK_HOME, else the newest under $ANDROID_HOME/ndk),
# `cargo install cargo-ndk` and `rustup target add --toolchain 1.99.1 aarch64-linux-android
# x86_64-linux-android` (the toolchain packages/widgets-rs/rust-toolchain.toml names). CARGO_TARGET_DIR, if set,
# is where cargo builds (else .widgets-rs/packages/widgets-rs/target).
# The generated Kotlin bindings are committed (src/main/kotlin/com/anomalyco/psychoscript and
# src/main/kotlin/ai/opencode/widgets), so builds without Rust compile; this script refreshes them. Without the
# libraries, widgets show "The widget runtime isn't available".
set -euo pipefail
REV=4415af3688a6fd11bc07aab4b091b1f488977f40
cd "$(dirname "$0")/.."
out=vendor/psychoscript
kotlin=feature/transcript/src/main/kotlin
bindings="com/anomalyco/psychoscript/psychoscript_mobile.kt com/anomalyco/psychoscript/PsychoScriptGpu.kt
  com/anomalyco/psychoscript/LiveFrames.kt ai/opencode/widgets/widget_mobile.kt"

checkout() {
  if [[ ! -d .widgets-rs ]]; then
    git init -q .widgets-rs
    git -C .widgets-rs remote add origin https://github.com/anomalyco/opencode
    git -C .widgets-rs config remote.origin.promisor true
    git -C .widgets-rs config remote.origin.partialclonefilter blob:none
    git -C .widgets-rs sparse-checkout set packages/widgets-rs packages/widget
  fi
  git -C .widgets-rs fetch -q --depth 1 --filter=blob:none origin "$REV"
  git -C .widgets-rs checkout -q --detach "$REV"
}

export PATH="$HOME/.cargo/bin:$PATH"
workspace="$PWD/.widgets-rs/packages/widgets-rs"
target="${CARGO_TARGET_DIR:-$workspace/target}"

if [[ "${1:-}" == "--host" ]]; then
  checkout
  (cd "$workspace" && CARGO_TARGET_DIR="$target/host" cargo rustc -q --release -p widget-mobile --lib --crate-type cdylib)
  rm -rf "$out/host"
  mkdir -p "$out/host"
  cp "$target"/host/release/libwidget_mobile.{dylib,so} "$out/host/" 2>/dev/null || true
  ls "$out"/host/libwidget_mobile.* >/dev/null 2>&1 || { echo "No host library in $target/host/release" >&2; exit 1; }
  echo "anomalyco/opencode $REV" > "$out/host/REVISION"
  echo "widget-mobile $REV for this machine in $out/host"
  exit 0
fi

if [[ "${1:-}" == "--prebuilt" ]]; then
  src="${2:?usage: scripts/psychoscript.sh --prebuilt <dir>}"
  grep -q "$REV" "$src/REVISION" || { echo "$src is $(cat "$src/REVISION" 2>/dev/null || echo unknown), not $REV" >&2; exit 1; }
  rm -rf "$out/jniLibs" "$out/kotlin" "$out/REVISION"
  mkdir -p "$out"
  cp -R "$src/jniLibs" "$src/kotlin" "$src/REVISION" "$out/"
else
  if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    ANDROID_NDK_HOME="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -n 1)"
    export ANDROID_NDK_HOME
  fi
  [[ -d "$ANDROID_NDK_HOME" ]] || { echo "No NDK: set ANDROID_NDK_HOME (r28 or later)" >&2; exit 1; }
  checkout
  CARGO_TARGET_DIR="$target" "$workspace/crates/widget-mobile/scripts/build-android.sh" "$PWD/$out"
fi

grep -q "$REV" "$out/REVISION" || { echo "Built $(cat "$out/REVISION"), not $REV" >&2; exit 1; }
for f in $bindings; do
  if [[ -f "$out/kotlin/$f" ]] && ! cmp -s "$out/kotlin/$f" "$kotlin/$f"; then
    mkdir -p "$(dirname "$kotlin/$f")"
    cp "$out/kotlin/$f" "$kotlin/$f"
    echo "Updated the committed bindings ($f) in $kotlin: commit them with the REV bump."
  fi
done
echo "widget-mobile $(cat "$out/REVISION")"
ls -l "$out"/jniLibs/*/
