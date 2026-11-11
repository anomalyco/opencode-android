#!/usr/bin/env bash
# Build, test and run OpenCode Android on the shared Android dev VM instead of this laptop.
# Run it from inside an opencode-android checkout or worktree. SKILL.md next to this file explains
# the workflow.
#
#   vm.sh sync                             copy this checkout to the VM (no .git, build outputs or ignored files)
#   vm.sh check [module...]                sync, then scripts/check.sh on the VM
#   vm.sh build [fast] [live] [benchmark]  sync, then build the APK on the VM
#   vm.sh emu up | down | status           lease and boot an emulator slot (FX_1-FX_6), release it, or show slots
#   vm.sh install [apk]                    install an APK from the VM checkout on the emulator and launch it
#   vm.sh shot <file.png> [full]           emulator screenshot saved here (540 px wide unless "full")
#   vm.sh rec start [seconds] | stop <file.mp4>   record the emulator screen (up to 180 s) and save it here
#   vm.sh texts [count]                    on-screen texts and descriptions with their tap coordinates
#   vm.sh tap <text>                       tap the first element whose text or description contains <text>
#   vm.sh adb <args...>                    adb against the emulator, run on the VM in the checkout
#   vm.sh lab start [checkpoint] | stop | ctl <path> [json]   session-lab server for the emulator
#   vm.sh phone [fast] [live] [benchmark]  sync, build, copy the APK here and install it on the phone
#   vm.sh run <command>                    run a shell command on the VM in the checkout
set -euo pipefail

HOST=${VM_HOST:-ubuntu@54.161.121.85}
# Your folder on the VM (~/<name>) and the prefix of your lanes.
ME=${VM_USER:-$(id -un)}
SKILL_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# One shared SSH connection, so every call after the first skips the handshake.
SSH_OPTS="-o ControlMaster=auto -o ControlPath=$HOME/.ssh/cm-android-vm -o ControlPersist=30m -o ConnectTimeout=15 -o ServerAliveInterval=30 -o BatchMode=yes"
# Ryan's worker script on the VM. It owns everything shared with his QA agents: the FX emulator
# slots, the virtual radio and the session-lab servers. Only Gradle runs outside it.
WORKER='$HOME/qa/bin/vm-worker.sh'
ENV_SETUP='export ANDROID_HOME=$HOME/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH;'

die() {
  echo "vm.sh: $*" >&2
  exit 1
}

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "run this from inside an opencode-android checkout"
[[ -f $ROOT/settings.gradle.kts && -f $ROOT/scripts/check.sh ]] || die "$ROOT is not an opencode-android checkout"
# Each checkout or worktree gets its own folder and emulator lease on the VM.
LANE=${VM_LANE:-$ME-$(basename "$ROOT")}
[[ $LANE =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]] || die "invalid lane name: $LANE"
# Ryan's worker only works with checkouts in ~/qa/src, so the lane lives there too.
DIR=qa/src/$LANE

# Quotes arguments so the VM's shell sees them exactly as they were given here.
quote() {
  if [[ $# -gt 0 ]]; then
    printf '%q ' "$@"
  fi
}

# Runs a shell command on the VM from the home folder, with the Android SDK and JDK 17 on PATH.
remote() {
  # shellcheck disable=SC2086
  ssh $SSH_OPTS "$HOST" "$ENV_SETUP $1"
}

# Runs a shell command on the VM in this lane's checkout.
in_dir() {
  remote "cd $DIR && { [ -f local.properties ] || printf 'sdk.dir=%s\n' \"\$ANDROID_HOME\" > local.properties; } && $1"
}

sync_tree() {
  # --delete removes files deleted here, so a removed Kotlin file can't keep compiling there.
  # Excluded paths (build outputs, local.properties) are left alone on the VM.
  rsync -a --delete --mkpath -e "ssh $SSH_OPTS" \
    --exclude=.git --exclude=build/ --exclude=.gradle/ --exclude=.kotlin/ --exclude=local.properties \
    --filter=':- .gitignore' \
    "$ROOT/" "$HOST:$DIR/"
  rsync -a --mkpath -e "ssh $SSH_OPTS" "$SKILL_DIR/gradle/" "$HOST:$ME/gradle/"
}

# Turns build options into Gradle arguments (INIT, TASK) and the APK path (APK).
parse_build() {
  TASK=:app:assembleDebug
  APK=app/build/outputs/apk/debug/app-debug.apk
  INIT=""
  for option in "$@"; do
    case $option in
      fast) INIT="$INIT -I \$HOME/$ME/gradle/phone-fast.init.gradle" ;;
      live) INIT="$INIT -I \$HOME/$ME/gradle/live-suffix.init.gradle" ;;
      benchmark)
        TASK=:app:assembleBenchmark
        APK=app/build/outputs/apk/benchmark/app-benchmark.apk
        ;;
      *) die "unknown build option: $option (use fast, live or benchmark)" ;;
    esac
  done
}

# Prints the app's version variables as `NAME=value ` pairs for the remote Gradle run, so a build
# can show a real version in Settings.
version_env() {
  local result=""
  local name
  for name in OC_VERSION_NAME OC_VERSION_CODE OC_VERSION_SUFFIX; do
    if [[ -n ${!name:-} ]]; then
      result+="$name=$(printf %q "${!name}") "
    fi
  done
  printf '%s' "$result"
}

build() {
  parse_build "$@"
  sync_tree
  in_dir "$(version_env)./gradlew --console=plain $INIT $TASK"
  echo "Built on the VM: $APK"
}

# Prints the FX slot this lane has leased, or nothing.
leased_slot() {
  remote "for f in \$HOME/qa/locks/FX_*/owner; do if [ -f \"\$f\" ] && [ \"\$(head -1 \"\$f\")\" = $LANE ]; then basename \"\$(dirname \"\$f\")\"; fi; done" | head -1
}

# Sets SLOT and SERIAL for this lane's emulator, or stops if it has none.
need_slot() {
  # A custom AVD booted by hand (another API level): use its serial and skip the lease.
  if [[ -n ${VM_SERIAL:-} ]]; then
    SLOT=custom
    SERIAL=$VM_SERIAL
    return
  fi
  SLOT=$(leased_slot)
  [[ -n $SLOT ]] || die "no emulator leased for $LANE; run: vm.sh emu up"
  SERIAL=emulator-$((5558 + 2 * ${SLOT#FX_}))
}

emu() {
  case ${1:-} in
    up)
      SLOT=$(leased_slot)
      if [[ -z $SLOT ]]; then
        # Waits for a free slot for up to 5 minutes; Ryan's QA agents use the same six.
        SLOT=$(remote "timeout 300 $WORKER allocate $LANE $LANE") || die "no emulator slot came free in 5 minutes; see: vm.sh emu status"
      fi
      SERIAL=emulator-$((5558 + 2 * ${SLOT#FX_}))
      remote "$WORKER boot $SLOT"
      echo "$SLOT is up as $SERIAL for $LANE"
      ;;
    down)
      need_slot
      remote "$WORKER release $SLOT $LANE"
      echo "Released $SLOT"
      ;;
    status)
      remote "for f in \$HOME/qa/locks/FX_*/owner; do [ -f \"\$f\" ] && echo \"\$(basename \"\$(dirname \"\$f\")\") leased by \$(sed -n 2p \"\$f\")\"; done; echo; adb devices | tail -n +2"
      ;;
    *) die "usage: vm.sh emu up | down | status" ;;
  esac
}

install_emu() {
  need_slot
  local apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
  # shellcheck disable=SC2086
  ssh $SSH_OPTS "$HOST" "$ENV_SETUP cd $DIR && bash -s -- $(quote "$SERIAL" "$apk")" <<'EOF'
set -euo pipefail
serial=$1
apk=$2
aapt2=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2
package=$("$aapt2" dump packagename "$apk")
if ! output=$(adb -s "$serial" install -r -t "$apk" 2>&1); then
  case $output in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *INSTALL_FAILED_VERSION_DOWNGRADE*)
      # Emulator app data is disposable: replace a build signed with another key or newer version.
      adb -s "$serial" uninstall "$package" >/dev/null
      adb -s "$serial" install -t "$apk"
      ;;
    *)
      echo "$output" >&2
      exit 1
      ;;
  esac
fi
echo "Installed $package on $serial"
adb -s "$serial" shell am start -W -n "$package/ai.opencode.app.MainActivity" | grep -E 'Status|TotalTime'
EOF
}

shot() {
  local file=${1:?usage: vm.sh shot <file.png> [full]}
  need_slot
  if [[ ${2:-} == full ]]; then
    remote "adb -s $SERIAL exec-out screencap -p" >"$file"
  elif [[ $SLOT == custom ]]; then
    remote "adb -s $SERIAL exec-out screencap -p | ffmpeg -nostdin -loglevel error -i pipe:0 -vf scale=540:-1 -frames:v 1 -f image2pipe -c:v png pipe:1" >"$file"
  else
    remote "$WORKER shot $SLOT" >"$file"
  fi
  echo "$file"
}

rec() {
  need_slot
  # screenrecord runs on the VM inside tmux, so it keeps recording between vm.sh calls.
  local session=vmrec-$LANE
  local device_file=/sdcard/vm-rec.mp4
  case ${1:-} in
    start)
      local seconds=${2:-60}
      [[ $seconds =~ ^[0-9]+$ ]] && ((seconds >= 1 && seconds <= 180)) || die "seconds must be 1-180"
      remote "tmux kill-session -t =$session 2>/dev/null; adb -s $SERIAL shell rm -f $device_file; tmux new -d -s $session 'adb -s $SERIAL shell screenrecord --bit-rate 6000000 --time-limit $seconds $device_file'"
      echo "Recording $SERIAL for up to $seconds s. Stop with: vm.sh rec stop <file.mp4>"
      ;;
    stop)
      local file=${2:?usage: vm.sh rec stop <file.mp4>}
      # SIGINT lets screenrecord finish the MP4 properly; then wait for it to exit.
      remote "adb -s $SERIAL shell pkill -INT screenrecord; for i in \$(seq 40); do tmux has -t =$session 2>/dev/null || break; sleep 0.25; done; sleep 0.5"
      remote "adb -s $SERIAL exec-out cat $device_file" >"$file"
      [[ -s $file ]] || die "the recording is empty"
      echo "$file"
      ;;
    *) die "usage: vm.sh rec start [seconds] | stop <file.mp4>" ;;
  esac
}

# Reads uiautomator XML on stdin. With no match it prints "x,y<TAB>'text' 'description'" for up to
# <limit> elements; with a match it prints only the centre of the first element that contains it.
UI_PARSER=$(
  cat <<'PY'
import re, sys
limit = int(sys.argv[1])
match = sys.argv[2] if len(sys.argv) > 2 else None
xml = sys.stdin.read()
printed = 0
for node in re.finditer(r"<node [^>]*>", xml):
    node = node.group(0)
    text = re.search(r' text="([^"]*)"', node)
    desc = re.search(r' content-desc="([^"]*)"', node)
    text = text.group(1) if text else ""
    desc = desc.group(1) if desc else ""
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not (text or desc) or bounds is None:
        continue
    x1, y1, x2, y2 = map(int, bounds.groups())
    centre = f"{(x1 + x2) // 2} {(y1 + y2) // 2}"
    if match is not None:
        if match in text or match in desc:
            print(centre)
            sys.exit(0)
        continue
    print(f"{centre.replace(' ', ',')}\t{text!r} {desc!r}")
    printed += 1
    if printed >= limit:
        break
if match is not None:
    sys.exit(1)
PY
)

ui_xml() {
  remote "adb -s $SERIAL shell uiautomator dump /data/local/tmp/vm-ui.xml >/dev/null && adb -s $SERIAL exec-out cat /data/local/tmp/vm-ui.xml"
}

texts() {
  need_slot
  ui_xml | python3 -c "$UI_PARSER" "${1:-80}"
}

tap() {
  local text=${1:?usage: vm.sh tap <text>}
  need_slot
  local position
  position=$(ui_xml | python3 -c "$UI_PARSER" 1 "$text") || die "nothing on screen contains: $text"
  remote "adb -s $SERIAL shell input tap $position"
  echo "tapped $text at $position"
}

lab() {
  need_slot
  # One lab port per slot (4151-4156), so lanes never share a server. Ryan's labs use others.
  [[ $SLOT != custom || -n ${VM_LAB_PORT:-} ]] || die "set VM_LAB_PORT with VM_SERIAL"
  local port=${VM_LAB_PORT:-$((4150 + ${SLOT#FX_}))}
  case ${1:-} in
    start)
      remote "$WORKER lab start $port $(quote "${2:-playground}")"
      echo "session-lab runs on VM port $port. Connect the emulator to http://10.0.2.2:$port"
      ;;
    stop)
      remote "$WORKER lab stop $port"
      ;;
    ctl)
      shift
      remote "$WORKER lab ctl $port $(quote "$@")"
      ;;
    *) die "usage: vm.sh lab start [checkpoint] | stop | ctl <path> [json]" ;;
  esac
}

phone() {
  parse_build "$@"
  sync_tree
  # Unless set, Settings on the phone shows which branch and commit this build came from.
  if [[ -z ${OC_VERSION_NAME:-} ]]; then
    local sha branch
    sha=$(git rev-parse --short HEAD)
    branch=$(git rev-parse --abbrev-ref HEAD)
    if [[ $branch == HEAD ]]; then
      OC_VERSION_NAME=$sha
    else
      OC_VERSION_NAME="$branch ($sha)"
    fi
  fi
  in_dir "$(version_env)./gradlew --console=plain $INIT $TASK"
  local cache=$HOME/.cache/android-vm/$LANE
  mkdir -p "$cache"
  # rsync only sends the parts of the APK that changed since the last copy.
  rsync -a -e "ssh $SSH_OPTS" "$HOST:$DIR/$APK" "$cache/vm.apk"
  # The VM signs with its own debug key. The phone's dev app was installed with this laptop's,
  # so re-sign with it: the update then keeps the app's data (pairing, settings).
  local build_tools
  build_tools=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/ | sort -V | tail -1)
  "$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android --out "$cache/phone.apk" "$cache/vm.apk"
  local serial=${VM_PHONE:-}
  if [[ -z $serial ]]; then
    serial=$(adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }')
  fi
  [[ -n $serial ]] || die "no phone on adb. Turn on Wireless debugging on the phone (same Wi-Fi as this laptop), then check: adb devices"
  adb -s "$serial" install -r -t "$cache/phone.apk"
  echo "Installed on $serial"
  # For speed builds, compile the whole app ahead of time instead of waiting for Android to do it.
  if [[ $INIT == *phone-fast* || $TASK == :app:assembleBenchmark ]]; then
    local package
    package=$("$build_tools/aapt2" dump packagename "$cache/phone.apk")
    adb -s "$serial" shell cmd package compile -m speed -f "$package"
    echo "Compiled $package ahead of time (speed)"
  fi
}

command=${1:-}
if [[ $# -gt 0 ]]; then
  shift
fi
case $command in
  sync) sync_tree ;;
  check)
    sync_tree
    in_dir "scripts/check.sh $(quote "$@")"
    ;;
  build) build "$@" ;;
  emu) emu "$@" ;;
  install) install_emu "$@" ;;
  shot) shot "$@" ;;
  rec) rec "$@" ;;
  texts) texts "$@" ;;
  tap) tap "$@" ;;
  adb)
    need_slot
    in_dir "adb -s $SERIAL $(quote "$@")"
    ;;
  lab) lab "$@" ;;
  phone) phone "$@" ;;
  run) in_dir "$*" ;;
  *)
    sed -n '2,21p' "${BASH_SOURCE[0]}"
    exit 2
    ;;
esac
