---
name: android-phone-build
description: "Put a build of OpenCode Android on the user's phone at full speed: R8-optimised, not debuggable, compiled ahead of time, installed over the dev app so pairing and settings survive. Use when asked to put, install or update the app on the phone, to try a branch or PR on the phone, or to get the most performant build onto it."
---

# Fast build on the phone

The phone has three OpenCode apps. Only ever update the dev one:

| Package | What it is |
| --- | --- |
| `ai.opencode.mobilecode` | The Play Store build. Never install over it or touch it. |
| `ai.opencode.mobilecode.debug` | The dev app. Every phone build goes here and replaces what was there. |
| `ai.opencode.mobilecode.benchmark` | Release-config benchmark builds (rarely used). |

Builds run on the Android VM through the `android-vm` skill's script. Load that skill too if you need anything beyond this.

```bash
REPO=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")   # the main checkout
V=$REPO/.agents/skills/android-vm/vm.sh
```

`$V` is the main checkout's copy of the script, so it works from any worktree, even one on an old branch or tag that predates these skills.

## Why `phone fast` is the fastest build

`$V phone fast` does all of this in one command (about 2 minutes, less when little changed):

1. Syncs the checkout to the VM and builds `:app:assembleDebug` with `gradle/phone-fast.init.gradle`, which makes the debug build work like release: R8 shrinks and optimises the code (`proguard-android-optimize`), it isn't debuggable (debuggable apps run much slower), and it's profileable so it can still be profiled.
2. Copies the APK back and re-signs it with this laptop's `~/.android/debug.keystore`, the key the phone's `.debug` app was first installed with. That's why the update keeps pairing and settings.
3. Installs with `adb install -r` over `.debug`.
4. Runs `adb shell cmd package compile -m speed -f ai.opencode.mobilecode.debug`, so Android compiles the whole app to native code now, instead of running it interpreted until background optimisation runs later.
5. Sets the version name to `<branch> (<sha>)` (or just the sha when detached), so Settings on the phone shows which build it is. Set `OC_VERSION_NAME` to override.

Never use a plain `$V phone` for the user's everyday build: it's debuggable and noticeably slower.

## Steps

1. **Check the phone is free.** If the user said they're using it, ask first and wait. Then check it's connected: `adb devices` should list the phone (not an `emulator-`) as `device`. If not, the user needs Wireless debugging on, on the same Wi‑Fi as this laptop.
2. **Pick what to build.**
   - One branch as it is: run from its worktree.
   - Several things together (for example `dev` plus open PRs, or the integration branch plus a feature branch): make a throwaway worktree and merge them there. Never commit or push from it.
     ```bash
     git -C "$REPO" fetch -q origin
     git -C "$REPO" worktree add --detach /tmp/opencode/phone-build origin/dev
     cd /tmp/opencode/phone-build && git merge --no-edit origin/<branch> [origin/<branch2> ...]
     ```
     Use `VM_LANE=$USER-phone-build` for it so it doesn't disturb another lane's build.
3. **Build and install:** `$V phone fast` (or `VM_LANE=$USER-phone-build $V phone fast`). It's done when the output ends with `Installed on …` and `Compiled ai.opencode.mobilecode.debug ahead of time (speed)`.
4. **Check it** if there's any doubt:
   ```bash
   adb shell dumpsys package ai.opencode.mobilecode.debug | grep -E 'versionName|lastUpdateTime|pkgFlags'
   ```
   `versionName` shows the branch and commit, `lastUpdateTime` is just now, and `pkgFlags` has no `DEBUGGABLE`.
5. **Clean up** a throwaway worktree: `git -C "$REPO" worktree remove --force /tmp/opencode/phone-build`.
6. **Tell the user** what's on the phone now (branch, commit, and anything merged in). The phone holds one `.debug` build, so the last install wins: if a subagent installed something else since, say so.

## Rules

- Never install over or uninstall `ai.opencode.mobilecode`.
- Never uninstall `.debug` to fix an install error without asking: it wipes the pairing. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a build signed with another key; ask the user.
- Subagents only install on the phone when told to, and never while the user is using it.

## Other phone builds

- `$V phone fast live`: the same fast build as a separate `.live` app next to `.debug`, for side-by-side comparisons. It starts with no pairing.
- `$V phone benchmark`: the `.benchmark` package with the release build's settings, for performance measurements.
- `$V phone`: a plain debuggable build, only when you need a debugger.

Measure performance on the phone, never on the VM emulators: they render in software.
