#!/usr/bin/env bash
# Records the before and after apps through a video's segments on this lane's VM emulator, then
# copies the raw clips into <video dir>/raw.
#   record.sh <video dir> [segment...]   (default: every segment in video.json)
# Run it from inside the checkout or worktree with the change, once the emulator is up, both apps are
# installed and session-lab is running (make.sh does all of that).
# FRESH=1 forgets the cached tap positions first; use it after a layout change.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
VIDEO=$(cd "${1:?usage: record.sh <video dir> [segment...]}" && pwd)
shift
ROOT=$(git rev-parse --show-toplevel)
# Your folder on the VM (~/<name>) and the prefix of your lanes.
ME=${VM_USER:-$(id -un)}
LANE=${VM_LANE:-$ME-$(basename "$ROOT")}
HOST=${VM_HOST:-ubuntu@54.161.121.85}
SSH_OPTS="-o ControlMaster=auto -o ControlPath=$HOME/.ssh/cm-android-vm -o ControlPersist=30m -o ConnectTimeout=15 -o ServerAliveInterval=30 -o BatchMode=yes"
REMOTE=$ME/videos/$(basename "$VIDEO")

remote() {
  # shellcheck disable=SC2086
  ssh $SSH_OPTS "$HOST" "$1"
}

[ -f "$VIDEO/segments.sh" ] || { echo "record.sh: $VIDEO/segments.sh is missing" >&2; exit 1; }
if [ $# -gt 0 ]; then
  segments="$*"
else
  segments=$(python3 "$HERE/captions.py" names "$VIDEO/video.json" | tr '\n' ' ')
fi

slot=$(remote "for f in \$HOME/qa/locks/FX_*/owner; do if [ -f \"\$f\" ] && [ \"\$(head -1 \"\$f\")\" = $LANE ]; then basename \"\$(dirname \"\$f\")\"; fi; done" | head -1)
[ -n "$slot" ] || { echo "record.sh: no emulator leased for $LANE (run vm.sh emu up from this checkout)" >&2; exit 1; }
n=${slot#FX_}
serial=emulator-$((5558 + 2 * n))
port=${VM_LAB_PORT:-$((4150 + n))}
echo "Recording on $slot ($serial), lab port $port: $segments"

remote "mkdir -p $REMOTE/raw"
if [ "${FRESH:-0}" = 1 ]; then
  remote "rm -f $REMOTE/raw/coords-*.txt"
fi
rsync -a -e "ssh $SSH_OPTS" "$HERE/runner.sh" "$VIDEO/segments.sh" "$HOST:$REMOTE/"
for variant in before after; do
  remote "cd $REMOTE && SERIAL=$serial PORT=$port bash runner.sh $variant $segments"
done

mkdir -p "$VIDEO/raw"
rsync -a -e "ssh $SSH_OPTS" "$HOST:$REMOTE/raw/" "$VIDEO/raw/"
echo "Raw clips in $VIDEO/raw"
