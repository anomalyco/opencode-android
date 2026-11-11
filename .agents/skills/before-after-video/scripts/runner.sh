#!/usr/bin/env bash
# Runs on the Android VM. Records one app through the named segments of a before/after video.
#   runner.sh <before|after> <segment...>
# Needs SERIAL (emulator serial) and PORT (session-lab port). Run it from the video folder on the VM:
# segments.sh there defines one seg_<name> function per segment, using the helpers below.
set -u

VARIANT=${1:?usage: runner.sh <before|after> <segment...>}
shift
case "$VARIANT" in
  before) PKG=ai.opencode.mobilecode.live ;;
  after) PKG=ai.opencode.mobilecode.debug ;;
  *) echo "variant must be before or after" >&2; exit 1 ;;
esac
SERIAL=${SERIAL:?set SERIAL}
PORT=${PORT:?set PORT}
ACTIVITY=ai.opencode.app.MainActivity
OUT=raw
mkdir -p "$OUT"
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

UI=/tmp/before-after-ui-$SERIAL.xml
COORDS=$OUT/coords-$VARIANT.txt
touch "$COORDS"
# SEGMENT is the segment being run. REC is 1 between rec_start and rec_stop. REHEARSE is 1 during a
# dry run that only fills the tap cache.
SEGMENT=""
REC=0
REC_PID=""
REHEARSE=0

a() { adb -s "$SERIAL" "$@"; }
log() { echo "[$VARIANT] $*" >&2; }

# Calls a session-lab control endpoint, with an optional JSON body.
lab() {
  local path=$1
  local body=${2:-}
  if [ -n "$body" ]; then
    curl --noproxy '*' -fsS -o /dev/null -H 'Content-Type: application/json' --data-binary "$body" "http://127.0.0.1:$PORT$path"
  else
    curl --noproxy '*' -fsS -o /dev/null "http://127.0.0.1:$PORT$path"
  fi
}

checkpoint() { lab /lab/select "{\"checkpoint\":\"$1\"}"; }

dump() {
  a shell uiautomator dump /data/local/tmp/before-after-ui.xml >/dev/null 2>&1
  a exec-out cat /data/local/tmp/before-after-ui.xml >"$UI"
}

# Prints "x y" for the first element in the last dump whose text or description matches.
# Mode: contains (default) or exact.
find_in_dump() {
  python3 - "$UI" "$1" "${2:-contains}" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
want, mode = sys.argv[2], sys.argv[3]
for node in re.finditer(r"<node [^>]*>", xml):
    node = node.group(0)
    text = re.search(r' text="([^"]*)"', node)
    desc = re.search(r' content-desc="([^"]*)"', node)
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if bounds is None:
        continue
    values = [text.group(1) if text else "", desc.group(1) if desc else ""]
    hit = False
    for value in values:
        if mode == "exact" and value == want:
            hit = True
        if mode == "contains" and want in value:
            hit = True
    if hit:
        x1, y1, x2, y2 = map(int, bounds.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

# Waits until an element is on screen and prints its centre.
#   where <text> [contains|exact] [tries]
where() {
  local text=$1
  local mode=${2:-contains}
  local tries=${3:-8}
  local i
  for i in $(seq "$tries"); do
    dump
    if find_in_dump "$text" "$mode"; then
      return 0
    fi
    sleep 0.5
  done
  log "not found: $text"
  return 1
}

# True if an element is on screen now.
has() {
  dump
  find_in_dump "$1" "${2:-contains}" >/dev/null
}

# Taps an element by its text or description.
#   tapt <text> [contains|exact] [tries]
# While recording, positions come from a per-app cache that the rehearsal fills, because each
# uiautomator dump takes 2 s or more and would stall the clip.
tapt() {
  local text=$1
  local mode=${2:-contains}
  local xy=""
  local key="$SEGMENT|$text"
  if [ "$REC" = 1 ]; then
    xy=$(awk -F '\t' -v k="$key" '$1 == k { print $2; exit }' "$COORDS")
  fi
  if [ -z "$xy" ]; then
    xy=$(where "$text" "$mode" "${3:-8}") || return 1
    if [ "$REC" = 1 ]; then
      printf '%s\t%s\n' "$key" "$xy" >>"$COORDS"
    fi
  fi
  # shellcheck disable=SC2086
  a shell input tap $xy
}

tapxy() { a shell input tap "$1" "$2"; }
back() { a shell input keyevent 4; }
# swipe <x1> <y1> <x2> <y2> [ms]
swipe() { a shell input swipe "$1" "$2" "$3" "$4" "${5:-500}"; }

# Types a phrase a word at a time so it reads like typing.
typew() {
  local first=1
  local word
  for word in $1; do
    if [ $first -eq 0 ]; then
      a shell input keyevent 62
    fi
    a shell input text "$word"
    first=0
    sleep 0.15
  done
}

# Starts recording the current segment. Call it once per segment, after the setup.
rec_start() {
  REC=1
  if [ "$REHEARSE" = 1 ]; then
    return 0
  fi
  a shell rm -f /sdcard/before-after-seg.mp4
  a shell screenrecord --size 720x1600 --bit-rate 8000000 /sdcard/before-after-seg.mp4 &
  REC_PID=$!
  sleep 1.2
}

rec_stop() {
  sleep 0.6
  REC=0
  if [ "$REHEARSE" = 1 ]; then
    return 0
  fi
  a shell pkill -INT screenrecord
  wait "$REC_PID" 2>/dev/null
  REC_PID=""
  sleep 1
  a pull /sdcard/before-after-seg.mp4 "$OUT/$VARIANT-$SEGMENT.mp4" >/dev/null
  log "saved $OUT/$VARIANT-$SEGMENT.mp4"
}

# Saves the current screen as the segment's clip, for screens where nothing moves: screenrecord
# writes only a blank first frame when the screen is still.
#   still [seconds]
still() {
  local seconds=${1:-4}
  if [ "$REHEARSE" = 1 ]; then
    return 0
  fi
  a exec-out screencap -p >"$OUT/$VARIANT-$SEGMENT.png"
  ffmpeg -v error -y -loop 1 -framerate 30 -t "$seconds" -i "$OUT/$VARIANT-$SEGMENT.png" \
    -vf scale=720:1600,format=yuv420p -c:v libx264 -crf 18 "$OUT/$VARIANT-$SEGMENT.mp4"
  log "saved $OUT/$VARIANT-$SEGMENT.mp4 (still)"
}

# Clears the app's data and opens it on the welcome screen.
fresh_start() {
  a shell am force-stop "$PKG"
  a shell pm clear "$PKG" >/dev/null
  a shell am start -n "$PKG/$ACTIVITY" >/dev/null
  where "Enter a Server Manually" contains 20 >/dev/null
  sleep 1.5
}

# From the welcome screen, adds session-lab as a server and waits for the session screen.
connect_lab() {
  tapt "Enter a Server Manually"
  sleep 1.5
  tapt "Name" exact || tapt "Name"
  sleep 0.6
  typew "Session lab"
  sleep 0.6
  a shell input keyevent 61
  sleep 0.6
  typew "http://10.0.2.2:$PORT"
  sleep 1.2
  back
  sleep 0.8
  tapt "Connect" exact
  where "Open sessions" contains 20 >/dev/null
}

# Restarts the app on the session screen, connecting it to session-lab first if needed.
launch() {
  a shell am force-stop "$PKG"
  a shell am start -n "$PKG/$ACTIVITY" >/dev/null
  local i
  for i in $(seq 40); do
    dump
    if find_in_dump "Open sessions" >/dev/null; then
      sleep 1.5
      return 0
    fi
    if find_in_dump "Enter a Server Manually" >/dev/null; then
      connect_lab
      sleep 1.5
      return 0
    fi
    sleep 0.5
  done
  log "the app never reached the session screen"
  return 1
}

# Restarts the app and opens session-lab's "Session rendering lab" session.
open_lab_session() {
  launch
  tapt "Open sessions"
  sleep 1.5
  tapt "Session rendering lab"
  sleep 3
}

# theme yes | no
theme() { a shell cmd uimode night "$1" >/dev/null; sleep 1; }

# SystemUI demo mode: fixed clock, full battery and wifi, no notifications, so both sides match.
demo_mode() {
  a shell settings put global sysui_demo_allowed 1
  local b="am broadcast -a com.android.systemui.demo -e command"
  a shell "$b enter" >/dev/null
  a shell "$b clock -e hhmm 0941" >/dev/null
  a shell "$b battery -e level 100 -e plugged false" >/dev/null
  a shell "$b network -e wifi show -e level 4 -e fully true" >/dev/null
  a shell "$b network -e mobile hide" >/dev/null
  a shell "$b notifications -e visible false" >/dev/null
}

finish() {
  a shell pkill -INT screenrecord >/dev/null 2>&1
  a shell setprop debug.opencode.voice_demo 0
  a shell setprop debug.opencode.lab_floating_scenes 0
  a shell cmd uimode night no >/dev/null
  a shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
}

# shellcheck source=/dev/null
source ./segments.sh

for segment in "$@"; do
  if ! declare -F "seg_$segment" >/dev/null; then
    log "segments.sh has no seg_$segment function"
    exit 1
  fi
done

trap finish EXIT
demo_mode
# Start from a clean app so it connects to this lane's lab, not a server left from an older lease.
fresh_start

for segment in "$@"; do
  SEGMENT=$segment
  # A segment with nothing in the tap cache gets a dry run first, so its recording never waits on a dump.
  if ! grep -q "^$segment|" "$COORDS"; then
    log "rehearsing $segment"
    REHEARSE=1
    "seg_$segment"
    REHEARSE=0
    REC=0
    printf '%s|\t-\n' "$segment" >>"$COORDS"
  fi
  log "recording $segment"
  "seg_$segment"
done
