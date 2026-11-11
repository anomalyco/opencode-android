---
name: android-vm
description: "Build, check, test and run OpenCode Android on the shared Android dev VM instead of this laptop: Gradle builds, scripts/check.sh, emulators, session-lab, screenshots, UI taps, and installs on the phone. Use for any build, test, emulator or phone-install step in the opencode-android repo or its worktrees, in place of local ./gradlew, scripts/check.sh and scripts/emu.sh."
---

# OpenCode Android on the dev VM

Do Android dev work on Ryan's Android dev VM, not on this laptop. The VM (`ubuntu@54.161.121.85`: Ubuntu, 32 cores, 61 GB RAM, KVM, Android SDK, emulators) builds faster and keeps the laptop free. Editing code, git, commits and `scripts/ios-sync.py` stay local. The VM gets a copy of the files, not the repo.

Everything goes through one script. Run it from inside the checkout or worktree you're working in:

```sh
REPO=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")   # the main checkout
V=$REPO/.agents/skills/android-vm/vm.sh
```

`$V` is the main checkout's copy of the script, so it works from any worktree, even one on an old branch or tag that predates these skills.

## How it works

- Each command syncs your working tree to the VM first. It uses rsync over one shared SSH connection, skipping `.git`, build outputs and ignored files. A sync takes about 0.5 s, so always run the script and never copy files by hand.
- Each checkout gets its own lane, named `<your user name>-<checkout folder name>`, for example `dan-opencode-android`. A lane has its own folder on the VM (`~/qa/src/<lane>`), its own emulator lease and its own session-lab port. Worktrees get separate lanes automatically. To pick a lane name yourself, set `VM_LANE`. Your own files on the VM (Gradle init scripts, baseline APKs, videos) live in `~/<your user name>/`; set `VM_USER` if your laptop user name differs from the one you want there.
- Emulators and session-lab go through Ryan's worker script on the VM (`~/qa/bin/vm-worker.sh`). It shares six emulator slots (`FX_1`–`FX_6`) between his QA agents and you.
- Phone builds run on the VM, but the APK is copied back here, re-signed with this laptop's debug key and installed with local `adb`. The phone's `ai.opencode.mobilecode.debug` app was installed with this laptop's key, so updates keep its data (pairing, settings).

## Commands

| Command | What it does | Typical time |
| --- | --- | --- |
| `$V check [module...]` | Runs `scripts/check.sh` (assemble, lint, unit tests), e.g. `$V check :feature:composer` | 24 s for `:core`, about 4.5 min for every module |
| `$V build [fast] [live] [benchmark]` | Builds the APK on the VM | 4 s incremental, about 1–2 min cold |
| `$V emu up` / `down` / `status` | Leases and boots an emulator, releases it, or shows who holds which slot | 25 s to boot |
| `$V install [apk]` | Installs the last debug build (or the given APK path in the VM checkout) on your emulator and launches it | 3 s |
| `$V texts [count]` | Lists on-screen text and content descriptions with tap coordinates | 1 s |
| `$V tap "<text>"` | Taps the first element whose text or description contains `<text>` | 1 s |
| `$V shot file.png [full]` | Saves an emulator screenshot here (540 px wide; `full` gives 1080 × 2400). Read it with the read tool | 1 s |
| `$V adb <args...>` | Runs adb against your emulator, e.g. `$V adb shell input text hello`, `$V adb logcat -d -t 500` | |
| `$V lab start [checkpoint]` / `stop` / `ctl <path> [json]` | Runs session-lab on the VM for your emulator | 2 s |
| `$V phone [fast] [live] [benchmark]` | Builds on the VM and installs on the phone | 17 s incremental |
| `$V run '<command>'` | Runs any shell command in the VM checkout, e.g. `$V run './gradlew :server:testDebugUnitTest --tests "*Retry*"'` | |
| `$V sync` | Only syncs | 0.5 s |

Build options:
- `fast`: builds the debug app with R8, not debuggable, so it runs at release speed but still installs over `.debug` and keeps its data. `phone fast` then compiles the whole app ahead of time on the phone (`cmd package compile -m speed`). This is the build to keep on the phone day to day, and to use for performance checks. It takes about 2 minutes.
- `live`: changes the package to `ai.opencode.mobilecode.live`, so a second build can sit next to `.debug`, for example to compare a baseline with a change.
- `benchmark`: builds the `benchmark` build type (`ai.opencode.mobilecode.benchmark`, release R8 build, profileable).

Debug, `fast` and `live` builds all write to `app/build/outputs/apk/debug/app-debug.apk`, so `install` installs whichever was built last. Switching between `fast` and a plain build makes Gradle reconfigure and redo some work. For long performance work, use a separate lane (`VM_LANE=$USER-fast $V phone fast`).

`build` passes `OC_VERSION_NAME`, `OC_VERSION_CODE` and `OC_VERSION_SUFFIX` from your shell on to Gradle, so `OC_VERSION_NAME=0.3.10 $V build live` makes Settings show that version instead of 0.1.0.

Gradle output can be long. Pipe it through `grep -v '^w:' | tail -20` to hide Kotlin warnings. Give builds and checks a long shell timeout (20 minutes for a full `check` or a cold build).

## Typical loops

**Verify a change.** Run `$V check <modules you touched>`, then `$V check` (every module) before committing.

**See it on an emulator:**
1. `$V emu up`. If all six slots are busy it waits up to 5 minutes; `$V emu status` shows who holds them.
2. `$V build`, then `$V install`.
3. Use a session-lab server: `$V lab start` (or `$V lab start <checkpoint>`). It prints the port, 4150 + the slot number, e.g. 4151 for `FX_1`. In the app, choose Enter a Server Manually and type `http://10.0.2.2:<port>` with `$V tap` and `$V adb shell input text ...`. Drive the lab with `$V lab ctl /lab/select '{"checkpoint":"stream-text"}'` or `$V lab ctl /lab/stream-text '{"cadence":"steady","scale":1.0,"paragraphs":2}'`. `$V lab ctl /lab/state` shows the state.
4. Look with `$V texts`, `$V tap "<label>"` and `$V shot /tmp/opencode/s.png`.
5. When finished: `$V lab stop` and `$V emu down`. Release the slot every time; Ryan's QA agents wait for the same slots.

The slot images keep app data from earlier leases. For a clean start, run `$V adb shell pm clear ai.opencode.mobilecode.debug`.

**Custom AVD / other API level.** The slots are all API 36. For another level, install its image with `~/android-sdk/cmdline-tools/latest/bin/sdkmanager`, create your own AVD with `avdmanager` (named after you, e.g. `DAN_API_33`, `-d pixel_8`, `hw.ramSize=3072`), and boot it yourself in your own tmux session on a port from 5580 up (slots use 5554–5570) with the worker's flags (`grep -n run_emulator -A8 ~/qa/bin/vm-worker.sh`). Then set `VM_SERIAL=emulator-<port>` (and `VM_LAB_PORT` for `lab`) so `install`, `shot`, `texts`, `tap`, `adb` and `rec` target it without a lease. Kill it with `adb -s emulator-<port> emu kill` when done. Never touch the FX_/QA_ AVDs.

**Try it on the phone.** Use `$V phone`, `$V phone fast` (performance) or `$V phone live` (next to `.debug`). The phone needs Wireless debugging on and to be on the same Wi-Fi as this laptop; check with `adb devices`. Set `VM_PHONE=<serial>` if more than one non-emulator device is attached. `phone` sets the app's version name to `<branch> (<sha>)` unless `OC_VERSION_NAME` is set, so Settings shows which build is installed. The `android-phone-build` skill has the full process for the user's everyday phone build.

**Performance.** The VM has no GPU, so its emulators render in software. Frame times, jank and startup numbers from them are meaningless. Measure on the phone with a `fast` or `benchmark` build.

## Rules on the shared VM

The VM is Ryan's; his QA agents run on it all the time.
- Use only your own lane, slot and lab port. Never touch slots `QA_A`/`QA_B`, other people's leases, other `lab-*` or `emu-*` tmux sessions, ports 4196/4197 (Ryan's OpenCode servers) or other `~/qa/src/*` folders.
- Never run `adb kill-server`, `adb reconnect` or `./gradlew --stop` on the VM. They break everyone else's sessions.
- Never install anything over `ai.opencode.mobilecode` (the Play test app) on the phone. On the phone, never uninstall `.debug` to fix a signing error without asking: it wipes the pairing.
- Don't change the VM's SDK, AVDs, `~/.gradle/gradle.properties` or Ryan's scripts. Ask Ryan.

## Troubleshooting

- **SSH hangs or times out.** The VM's firewall only allows known IPs, and this laptop's IP has probably changed. Get it with `curl -s ifconfig.me` and send it to Ryan (Slack DM).
- **`no emulator leased for <lane>`.** Run `$V emu up` from the same checkout; leases are per lane.
- **`Lab <port> is already running`.** Run `$V lab stop` first. A `curl: (7) Failed to connect` line during `lab start` is normal; the worker retries until the server is up.
- **Phone install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** The app on the phone was signed with another key. Ask the user before uninstalling.
- **A build fails on the VM but not locally, or the other way round.** Run `$V sync` and retry. Files matched by `.gitignore` are never synced or deleted, so a file that became ignored can linger on the VM; remove it with `$V run 'rm <path>'`.
- **Session-lab behaves differently from iOS.** The VM runs Ryan's copy of the iOS scripts in `~/android-qa/ios-lab`, which can lag behind the iOS repo.
