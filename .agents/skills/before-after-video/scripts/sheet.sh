#!/usr/bin/env bash
# Contact sheet of one segment's raw clips (before on top, after below), one frame per STEP seconds.
#   sheet.sh <video dir> <segment> [step]   -> /tmp/opencode/cs/<video>-<segment>.png
set -euo pipefail
VIDEO=$(cd "${1:?usage: sheet.sh <video dir> <segment> [step]}" && pwd)
seg=${2:?usage: sheet.sh <video dir> <segment> [step]}
step=${3:-1.5}
out=/tmp/opencode/cs
name=$(basename "$VIDEO")-$seg
mkdir -p "$out"
for v in before after; do
  ffmpeg -v error -y -i "$VIDEO/raw/$v-$seg.mp4" -vf "fps=1/$step,scale=160:-1,tile=14x1:color=black" -frames:v 1 "$out/$name-$v.png"
done
ffmpeg -v error -y -i "$out/$name-before.png" -i "$out/$name-after.png" -filter_complex vstack "$out/$name.png"
echo "$out/$name.png"
