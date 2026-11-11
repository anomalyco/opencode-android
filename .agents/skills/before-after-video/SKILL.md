---
name: before-after-video
description: "Make a captioned side-by-side before/after video of an OpenCode Android UI change: the live release on the left, the new build on the right, recorded segment by segment on the shared Android VM emulator with scripted taps. Use when asked for a before/after, comparison or demo video of a UI change in the opencode-android repo or its worktrees, or to re-record or re-caption one."
---

# Before/after videos of an Android UI change

The video shows two phone recordings side by side: the release build on the left and the change on the right. A header above names each side, and a caption band below says what changed in each segment. Both apps run on one VM emulator, through the same scripted taps for each segment, so the two sides stay in step. The first one made this way is the desktop-restyle video (PR #21).

All emulator work goes through the `android-vm` skill. Load it and follow its shared-VM rules.

Scripts live in the main checkout's copy of this skill:

```sh
REPO=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")   # the main checkout
S=$REPO/.agents/skills/before-after-video/scripts
```

Run them from inside the opencode-android checkout or worktree that has the change, because the lane and emulator come from that checkout.

## Quick start

1. Make a video folder, outside the repo, normally `$REPO/../shots/<name>/`, and copy in `example/video.json` and `example/segments.sh` from this skill.
2. Edit both files. `video.json` holds the header labels and the segment order, titles and captions. `segments.sh` holds one `seg_<name>` function per segment with the taps.
3. From the worktree, run `$S/make.sh $REPO/../shots/<name>`.

`make.sh` does everything in one go:
- builds the "before" APK from the newest `v*` release tag, or reuses the VM copy at `~/<your user name>/baseline/<tag>-live.apk`
- leases an emulator
- installs the release (`ai.opencode.mobilecode.live`)
- builds and installs the worktree (`ai.opencode.mobilecode.debug`)
- sets each app's version name so Settings tells the two apart: the release shows its version (e.g. `0.3.10`), and the change shows its branch and commit (e.g. `my-branch (1a2b3c4)`)
- starts session-lab
- records every segment for both apps
- composes `<video dir>/before-after.mp4`
- releases the emulator and the lab

Options:
- `BASELINE=v0.3.8` picks another release tag. A tag is built once, in a `wt-release-<tag>` worktree next to the repo.
- `KEEP=1` leaves the emulator and lab running, so you can re-record single segments.
- `FRESH=1` forgets the cached tap positions first.

A 12-segment video takes about 11 minutes to record. Recording a segment for the first time takes about twice as long (see below).

## video.json

```json
{
  "left": "v0.3.10 release",
  "right": "New (my-branch)",
  "segments": [
    { "name": "settings", "title": "Settings", "caption": "One or two plain sentences on what changed here." }
  ]
}
```

- **Labels:** name the release tag on the left and the branch on the right.
- **Segments:** names are lowercase with dashes, and each needs a matching `seg_<name>`. They play in the listed order.
- **Captions:** keep them to two lines. They wrap automatically, `\n` forces a break, and longer ones print a warning.
- **What to write:** name the visible change, for example "Title bars: flat, with a hairline that appears on scroll". If something stays the same on purpose, say so.
- **Length:** aim for 60–100 s in total.

## Writing segments

`runner.sh` runs on the VM and loads `segments.sh` with these helpers:

| Helper | Does |
| --- | --- |
| `rec_start` / `rec_stop` | Records the current segment. Call each once, after the setup. |
| `still [seconds]` | Uses a screenshot as the clip, for screens where nothing moves (screenrecord gives a blank frame then). |
| `tapt "<text>" [exact]` | Taps the element whose text or content description matches. |
| `tapxy x y`, `swipe x1 y1 x2 y2 [ms]`, `back`, `typew "words"` | Raw input. `typew` types a word at a time. |
| `where "<text>"` / `has "<text>"` | Waits for an element and prints its centre, or checks for it. Both are slow (2 s or more), so use them outside the recording. |
| `launch` | Restarts the app on the session screen, connecting it to session-lab if it is on the welcome screen. |
| `open_lab_session` | Restarts the app and opens the "Session rendering lab" session. |
| `fresh_start` | Clears app data and opens the welcome screen. `connect_lab` then adds session-lab through Enter a Server Manually. |
| `checkpoint <name>`, `lab <path> [json]` | Sets session-lab state, e.g. `checkpoint permission`, `lab /lab/other-session '{"action":"many","count":12}'`. |
| `theme yes` / `theme no` | Dark or light mode. |
| `a <adb args>` | adb on this emulator, e.g. `a shell setprop debug.opencode.voice_demo 1`. `$PKG` is the current app. |

Rules for segments:
- Each segment sets up its own starting state before `rec_start`, usually a `checkpoint` plus `launch` or `open_lab_session`. A segment can run on its own, and it runs twice the first time.
- Use `sleep` to pace each step so the state can be read. Don't slow the video down to hide choppy animation: the emulator renders in software.
- **Tap cache:** while recording, `tapt` reads positions from a cache, `raw/coords-<before|after>.txt` on the VM, because a live lookup would stall the clip. A segment that isn't in the cache yet is rehearsed once without recording to fill it. After a layout change, re-record with `FRESH=1`.
- Before and after have separate caches, so a button may sit in a different place in each app. A swipe or `tapxy` uses the same coordinates on both sides.
- Undo any switches you change. The runner already resets dark mode, demo mode, `debug.opencode.voice_demo` and `debug.opencode.lab_floating_scenes` when it exits.

The example's segments cover the welcome screen, the New Server form, the transcript, tools, composer, model picker, drawer, Settings, prompt cards, a voice call and dark mode. Copy from them.

## Iterating

With the emulator kept (`KEEP=1`, or `vm.sh emu up`, both installs and `vm.sh lab start` done by hand):
- `$S/record.sh <video dir> <segment...>` re-records only the named segments.
- `$S/sheet.sh <video dir> <segment>` makes a contact sheet of the two raw clips, before on top and after below. Read it to check that each tap landed and both sides show the same state.
- `$S/compose.sh <video dir> [output.mp4]` rebuilds the video from the raw clips in under a minute. For a caption-only change, just edit `video.json` and run this.
- To check a frame of the result, use `ffmpeg -ss <t> -i before-after.mp4 -frames:v 1 /tmp/opencode/f.png`, then read the image.

When done, run `vm.sh lab stop` and `vm.sh emu down`.

## Output

The output is H.264 yuv420p with faststart, at 1160 px wide: two 576×1280 phones with an 8 px divider. Twelve segments came to about 8 MB. Keep it under 20 MB. `segments/` holds each composed segment clip, and `raw/` holds the recordings.

## Limits

- The voice-call demo is too heavy for software rendering: its transcript lines never appear. Also, tapping End froze v0.3.8, so the example force-stops the app instead.
- Anything that needs the camera (the pairing scanner) can't be recorded.
- Performance is never shown fairly on the emulator. Don't caption speed differences.
