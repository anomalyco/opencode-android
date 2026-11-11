---
name: ui-review
description: "Review and polish visible UI changes in OpenCode Android against the live release: a quality bar (beautiful, not just on-spec), before/after screenshots in light and dark, honest Better/Same/Worse verdicts, option pairs for judgement calls, and a live side-by-side gallery. Use when restyling or changing any screen, auditing how the app looks, or when asked for before/after screenshots of UI work in the opencode-android repo or its worktrees."
---

# UI review

UI work isn't done when it matches a spec. It's done when every changed screen looks at least as good as the live release, in light and dark, and a good product designer would ship it. This skill is the bar and the process for showing that.

Use the `android-vm` skill for builds, the emulator and screenshots (`$V shot`, `$V rec`), and the `before-after-video` skill if the user wants a video instead of a gallery.

## Design language

The app follows the OpenCode desktop app's design where it can (the `anomalyco/opencode` repo, `packages/app` and `packages/session-ui`; check its CSS tokens rather than guessing). In short: flat surfaces, hairline edges, soft shadows on floating things, small consistent radii, Inter, neutral black/white primary actions, one blue accent (`#3B5CF6`) for selection, switches that are on, and focus. Where a literal copy of a desktop component looks worse on a phone, adapt it until it looks good. The desktop look is the language, not a checklist.

## The bar

- Every "after" must look at least as polished as its "before" in both themes, and ideally better. If the release looks better, the work isn't done.
- Check hierarchy (what you see first), contrast, spacing rhythm, alignment, visual weight, and consistency with the screens around it.
- Keep what made the old version lovely unless the new one is clearly better. (The call orb is unchanged from iOS on purpose.)

**Rules that came out of real mistakes:**
- **Surfaces separate from the page.** Anything that floats or groups (cards, sheets, menus, dialogs, the call panel) needs a visible edge in both themes: a slightly different tone plus a hairline and/or soft shadow. Never the page colour with nothing around it. (A light-mode call card once vanished completely.)
- **Contrast through edges, not heavy fills.** Reach 3:1 for controls with outlines, not dark grey blobs. (An off switch with a heavy dark thumb looked worse than a light one with an outlined track.)
- **Restraint.** Few colours, one accent at a time, quiet surfaces, no harsh saturated rims or bright reds on near-white.
- **Light and dark are both first-class.** Judge each one on its own; don't just invert.
- **Don't clutter.** No help paragraphs between controls, no duplicate subtitles in menus, no fake handles that suggest a drag that doesn't exist. A page that fits must not scroll or bounce.

## Process

1. **Baseline.** "Before" is the live release: build it with the `before-after-video` skill's `scripts/baseline.sh` (newest `v*` tag, `.live` package, so it installs next to `.debug`). Install both on the same emulator and set up the same state (session-lab scenario, scroll position, keyboard, theme) before each pair.
2. **Capture both themes** (`adb shell cmd uimode night yes|no`) unless the screen truly looks the same in both (for example the always-dark camera). Use video (`$V rec`) when motion is the point. Turn on SystemUI demo mode for a clean status bar, and undo every switch you change.
3. **Critique before captioning.** Look at the before and after side by side with the read tool and judge the after honestly against the bar. If it's worse anywhere, iterate first. Check text closely: missing glyphs (Inter lacks some, like ⋮), cut-off labels, odd gaps.
4. **Give each item a verdict:** Better, Same or Worse than the release, on caption line 2. Report Worse items honestly, ranked worst first. For an audit of existing work, stop after the ranked list and fix only after the user agrees.
5. **Offer option pairs for real judgement calls** (`NN-slug-option-a`, `NN-slug-option-b`), recommend one, and let the user pick. Once picked, delete the losing item and keep one.
6. **Revise in place.** Overwrite the same `NN-slug-…-after.*` file and update its caption, so the gallery always shows the current state.
7. **Shoot from the combined build.** When several branches change the UI at once, merge them into one integration branch and take every "after" from that, so a switch or title bar in the background of your screen looks the way the other agent left it. After each merge, retake any "after" captured before it.
8. **Check the emulator's limits.** The VM emulators render in software, so animations are choppy and some heavy screens (the voice demo) don't keep up. Say so in captions, and check motion and performance on the phone (`android-phone-build` skill).

## Files and the gallery

One folder per review, one subfolder per workstream, outside the repo, e.g. `$REPO/../shots/<review>/<workstream>/` (`$REPO` is the main checkout):

```
NN-slug-light-before.png   NN-slug-light-after.png    (or .mp4)
NN-slug-dark-before.png    NN-slug-dark-after.png
NN-slug-light.txt          line 1: title, no notes in it; line 2: verdict and what changed; more lines optional
```

Light and dark must share the exact slug (the `NN-` number may differ). Serve the gallery and open it in a new Chrome window:

```bash
REPO=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")   # the main checkout
G=$REPO/.agents/skills/ui-review/gallery.py
setsid nohup python3 $G $REPO/../shots/<review> --port 8766 \
  --before-label "v0.3.10 release" --title "OpenCode Android: <review>" \
  [--repo $REPO --branch <integration branch>] \
  > /tmp/opencode/gallery.log 2>&1 &
(setsid /opt/google/chrome/chrome --new-window http://127.0.0.1:8766/ >/dev/null 2>&1 &)   # macOS: open http://127.0.0.1:8766/
```

Each item is one row: light before/after, then dark before/after, with placeholders for missing shots. It reloads itself as files arrive. With `--repo` and `--branch`, any "after" older than the integration branch's last update gets a red "stale" badge. Pick a free port (`ss -ltn`), and stop the server when the review is over.

For a quick one-off review without a server, a static `index.html` with the same columns, opened over `file://`, is fine.

## Sharing results

- **PRs:** add a small before/after image table (light and dark) to the PR body. Upload images through the browse tool on the PR's comment box to get `user-attachments` URLs, then clear the draft.
- **Slack:** upload to the user's self-DM or the relevant channel with the Slack upload flow.

## Report

Per item: the verdict, what changed, anything still not as good as the release, and the decisions for the user (with your recommendation). Keep it short; the gallery shows the rest.
