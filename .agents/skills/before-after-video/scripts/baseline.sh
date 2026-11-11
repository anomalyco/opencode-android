#!/usr/bin/env bash
# Prints the VM path of the "before" APK for a release tag, building it on the VM first if needed.
#   baseline.sh [tag]   (default: the newest v* release tag)
# Run it from inside any opencode-android checkout or worktree. The APK uses the .live package, so it
# installs next to the .debug build of the change. Build output goes to stderr.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
V=$(cd "$HERE/../../android-vm" && pwd)/vm.sh
HOST=${VM_HOST:-ubuntu@54.161.121.85}
# Your folder on the VM (~/<name>) and the prefix of your lanes.
ME=${VM_USER:-$(id -un)}
SSH_OPTS="-o ControlMaster=auto -o ControlPath=$HOME/.ssh/cm-android-vm -o ControlPersist=30m -o ConnectTimeout=15 -o ServerAliveInterval=30 -o BatchMode=yes"

die() {
  echo "baseline.sh: $*" >&2
  exit 1
}

REPO=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
# Only release tags: main-latest moves on every push, so a plain --tags fetch refuses to update it.
if ! git -C "$REPO" fetch --quiet origin 'refs/tags/v*:refs/tags/v*' 2>/dev/null; then
  echo "baseline.sh: could not fetch tags, using the local ones" >&2
fi
TAG=${1:-$(git -C "$REPO" tag --list 'v*' --sort=-v:refname | grep -v -- - | head -1)}
[ -n "$TAG" ] || die "no release tags found"
git -C "$REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "no tag $TAG"

APK=/home/ubuntu/$ME/baseline/$TAG-live.apk
# shellcheck disable=SC2086
if ssh $SSH_OPTS "$HOST" "test -s $APK"; then
  echo "$APK"
  exit 0
fi

WORKTREE=$(dirname "$REPO")/wt-release-$TAG
LANE=$ME-wt-release-$TAG
if [ ! -d "$WORKTREE" ]; then
  git -C "$REPO" worktree add --detach "$WORKTREE" "$TAG" >&2
fi
echo "baseline.sh: building $TAG on the VM (lane $LANE)" >&2
# The version name makes the app's Settings show the release, e.g. "0.3.10", instead of 0.1.0.
(cd "$WORKTREE" && OC_VERSION_NAME=${TAG#v} VM_LANE=$LANE "$V" build live 2>&1 | grep -v '^w:' | tail -3) >&2
# shellcheck disable=SC2086
ssh $SSH_OPTS "$HOST" "mkdir -p $ME/baseline && cp qa/src/$LANE/app/build/outputs/apk/debug/app-debug.apk $APK"
echo "$APK"
