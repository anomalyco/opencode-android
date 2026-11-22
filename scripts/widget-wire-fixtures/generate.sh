#!/usr/bin/env bash
# Regenerates feature/transcript/src/test/resources/widget-wire: the committed workloads of psychoscript-mobile at
# scripts/psychoscript.sh's REV, each as JSON and as the runtime's binary format (see that folder's README.md).
#
#   scripts/widget-wire-fixtures/generate.sh
#
# Builds psychoscript-mobile for this machine (cargo, the toolchain psychoscript names) in .psychoscript/, then runs
# Fixtures.kt on the JVM (JDK 21: JAVA_HOME) through the app's committed bindings.
set -euo pipefail
cd "$(dirname "$0")/../.."
REV=$(sed -n 's/^REV=//p' scripts/psychoscript.sh)
export PATH="$HOME/.cargo/bin:$PATH"
[[ -d .psychoscript ]] || git clone https://github.com/anomalyco/psychoscript .psychoscript
git -C .psychoscript fetch -q origin "$REV"
git -C .psychoscript checkout -q --detach "$REV"
target="$PWD/.psychoscript/rust/target/host"
(cd .psychoscript/rust && CARGO_TARGET_DIR="$target" cargo rustc -q --release -p psychoscript-mobile --lib --crate-type cdylib)
out=$(mktemp -d)
./gradlew -q -p scripts/widget-wire-fixtures run \
  -Dorg.gradle.jvmargs=-Xmx1g \
  --args="$PWD/.psychoscript/rust/crates/psychoscript-mobile/tests/fixtures/workloads $out" \
  -PjnaPath="$target/release"
dest=feature/transcript/src/test/resources/widget-wire
for f in "$out"/*; do gzip -9 -n -c "$f" > "$dest/$(basename "$f").gz"; done
rm -rf "$out"
echo "widget-wire fixtures from psychoscript $REV in $dest"
