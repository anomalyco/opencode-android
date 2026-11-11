#!/usr/bin/env bash
# Makes a before/after video in one command: gets the release APK, builds this checkout, installs both
# on a VM emulator, records every segment, composes the video, then releases the emulator.
#   make.sh <video dir> [output.mp4]   (default output: <video dir>/before-after.mp4)
# Run it from inside the opencode-android checkout or worktree that has the change.
#   BASELINE=v0.3.8   release tag for the "before" side (default: the newest v* tag)
#   KEEP=1            leave the emulator and lab running afterwards, to re-record single segments
#   FRESH=1           forget cached tap positions first (after a layout change)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
V=$(cd "$HERE/../../android-vm" && pwd)/vm.sh
VIDEO=$(cd "${1:?usage: make.sh <video dir> [output.mp4]}" && pwd)
OUTPUT=${2:-$VIDEO/before-after.mp4}

for file in video.json segments.sh; do
  [ -f "$VIDEO/$file" ] || { echo "make.sh: $VIDEO/$file is missing" >&2; exit 1; }
done
python3 "$HERE/captions.py" names "$VIDEO/video.json" >/dev/null

before_apk=$("$HERE/baseline.sh" ${BASELINE:-})
echo "Before: $before_apk"

"$V" emu up
if [ "${KEEP:-0}" != 1 ]; then
  trap '"$V" lab stop >/dev/null 2>&1 || true; "$V" emu down || true' EXIT
fi
"$V" install "$before_apk"
# The new app's Settings shows the branch and commit, e.g. "my-branch (1a2b3c4)", instead of 0.1.0.
branch=$(git branch --show-current)
commit=$(git rev-parse --short HEAD)
if [ -n "$branch" ]; then
  after_version="$branch ($commit)"
else
  after_version=$commit
fi
OC_VERSION_NAME=$after_version "$V" build 2>&1 | grep -v '^w:' | tail -3
"$V" install
"$V" lab stop >/dev/null 2>&1 || true
"$V" lab start

"$HERE/record.sh" "$VIDEO"
"$HERE/compose.sh" "$VIDEO" "$OUTPUT"
