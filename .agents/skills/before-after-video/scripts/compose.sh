#!/usr/bin/env bash
# Puts each segment's before and after clips side by side under the header and caption band, then
# joins the segments in video.json order.
#   compose.sh <video dir> [output.mp4]   (default output: <video dir>/before-after.mp4)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
VIDEO=$(cd "${1:?usage: compose.sh <video dir> [output.mp4]}" && pwd)
OUTPUT=${2:-$VIDEO/before-after.mp4}
RAW=$VIDEO/raw
SEGDIR=$VIDEO/segments
OVERLAYS=$SEGDIR/overlays
# Seconds each segment holds its last frame.
HOLD=0.6
# Seconds cut from the start of each clip (screenrecord warm-up and the first pause).
TRIM=1.3
MIN_SECONDS=3.8
CRF=${CRF:-22}

rm -rf "$OVERLAYS"
mkdir -p "$SEGDIR"
rm -f "$SEGDIR"/*.mp4
eval "$(python3 "$HERE/captions.py" overlays "$VIDEO/video.json" "$OVERLAYS")"

duration() {
  local d
  d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$1")
  if [ "$d" = "N/A" ] || [ -z "$d" ]; then
    d=0
  fi
  echo "$d"
}

list=$SEGDIR/concat.txt
: >"$list"
for overlay in "$OVERLAYS"/*.png; do
  base=$(basename "$overlay" .png)
  name=${base#*-}
  before=$RAW/before-$name.mp4
  after=$RAW/after-$name.mp4
  if [ ! -f "$before" ] || [ ! -f "$after" ]; then
    echo "skip $name (missing clip)"
    continue
  fi
  db=$(duration "$before")
  da=$(duration "$after")
  trim=$TRIM
  if [ "$db" = 0 ] || [ "$da" = 0 ]; then
    trim=0
  fi
  total=$(python3 -c "print(round(max($db - $trim, $da - $trim, $MIN_SECONDS) + $HOLD, 2))")
  # The shorter clip holds its last frame until the longer one ends.
  phone="setpts=PTS-STARTPTS,fps=30,trim=start=$trim,setpts=PTS-STARTPTS,scale=${PHONE_W}:${PHONE_H}:flags=lanczos,setsar=1,tpad=stop_mode=clone:stop_duration=$total,trim=duration=$total"
  ffmpeg -v error -y -i "$before" -i "$after" -loop 1 -framerate 30 -i "$overlay" -filter_complex "
    [0:v]$phone[b];
    [1:v]$phone[a];
    color=white:s=${WIDTH}x${HEIGHT}:r=30:d=$total[bg];
    [bg][b]overlay=0:${HEADER_H}[t1];
    [t1][a]overlay=$((PHONE_W + GAP)):${HEADER_H}[t2];
    [t2][2:v]overlay=0:0:shortest=1,format=yuv420p[v]" \
    -map "[v]" -t "$total" -r 30 -c:v libx264 -preset medium -crf "$CRF" -pix_fmt yuv420p "$SEGDIR/$base.mp4"
  echo "file '$SEGDIR/$base.mp4'" >>"$list"
  echo "$base: before ${db}s, after ${da}s, segment ${total}s"
done

[ -s "$list" ] || { echo "compose.sh: no segment has both clips in $RAW" >&2; exit 1; }
ffmpeg -v error -y -f concat -safe 0 -i "$list" -c copy -movflags +faststart "$OUTPUT"
echo "$OUTPUT: $(duration "$OUTPUT")s, $(du -h "$OUTPUT" | cut -f1)"
