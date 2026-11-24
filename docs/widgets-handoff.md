# Agent widgets on Android: handoff

Contract: opencode `specs/v2/widgets.md` (`widgets` branch). Reference client: rune-desktop
`crates/opencode-widget`, `crates/opencode-widget-view`. Parity rows: `docs/parity/transcript.md`
and `server.md` ("Agent widgets").

## State

Builds, installs and runs on the emulator against the widgets dev server and session-lab's
`widgets` checkpoint. Every built-in renders and answers: `suggestions` (carousels, images,
note), `form` (input, slider, checkbox, select), `confirm` (live: Deploy → Sending →
Submitted, `◈ Responded to "…"`), `chart` (bar and line; animated intro, press/drag tooltip,
tap to submit) and `backdrop` (GLES 3 shader, speed slider).

- Runner: `psychoscript-mobile`, the PsychoScript language alone (anomalyco/psychoscript, branch `mobile`,
  crate `rust/crates/psychoscript-mobile`, package `com.anomalyco.psychoscript`). `NativeAgentWidgetEngine`
  compiles `program + "\nreturn __widget_step(__input())"` once and `run`s steps at 250 ms and draws at 8 ms
  (a draw timeout counts as an overrun). Everything else widget-shaped is the app's: step inputs, output
  parsing and validation, the 2,000-command cap, rendering.
- Canvas GPU mode (a `canvas` with `wgsl`): `AgentWidgetEngine.shaderCanvas(wgsl, surface, w, h)` wraps the
  runner's `ShaderCanvas`, which compiles and presents on its own thread (Vulkan) into a `TextureView`'s
  surface. `AgentWidgetGPUCanvas` records frames with `render(time, scale, pointer, values)` from
  `withFrameNanos` at `fps` (once, and on `values` changes, at `fps: 0`), paused off screen / list not resumed;
  a WGSL error is reported once in phase `gpu` with the template's line, no GPU or a GPU hang is a client error
  (poster/alt); the slow-frame stop (50 ms × 3) is logged and keeps the last frame.
- GPU canvases present into a `TextureView` (`setOpaque` from `Live(opaque)`), and an opaque live canvas full screen
  into a `SurfaceView` (`GpuSurfaceHost`): a TextureView frame re-records the Compose root and redraws the window
  (AndroidView turns it into a layer invalidation); a SurfaceView frame costs the app's threads nothing. The
  SurfaceView sits below the full-screen window, so HUD layers draw over it, and a translucent one would show the
  transcript's window through it: translucent canvases (every `wgsl` one) stay on a TextureView full screen.
- Live frames go out on `AgentWidgetFrameThread` (its own Looper and Choreographer); frame requests are spotted on
  the run's thread, never the main thread.
- Live GPU canvases (`canvas` with `gpu: true`, wire prop 38, widgets-gpu.md): `AgentWidgetLiveGPUCanvas`
  attaches a `TextureView` surface under the canvas's key (`AgentWidgetNode.canvasKey`) to the live run
  (`attachSurface`, again on resize; `detachSurface` before the surface goes). The driver keeps a stack per key
  (inline and full screen: the newest presents) and reattaches after a restart or a snapshot resume. The run's
  `onGpuCanvas` states drive the placeholder (`poster`, else `alt`, else "GPU canvas not supported").
  A surface failure ("the surface was lost", "the surface failed: …") is live again once Android gives a new
  surface for the key; one reported while the `Surface` is still valid is logged.
- Canvas: step and draw-list decode on the widget's lane, own program copy while animating,
  frames only while visible, `Budget` errors count toward the 3-overrun stop.

## Diagnostics (dev mode)

- In dev mode only (`AgentWidgetCapabilities.isDevMode`: a debug build or Developer Mode), `AgentWidgetDiagnostics`
  logs to `AppLog.widget`, logcat tag `OpenCode/widget`. It logs every program error (template `name@version`, phase,
  line and column, message), every client error with its exact reason (posts, load, step, live, fetch transfers,
  GPU, and fetches the runtime denied: `CapabilityDenied: …`), every GPU canvas state change with its reason, and the
  GPU's start once per process (ready, or the `Unavailable` reason: Android version, adapter, Vulkan version, limits,
  wgpu's device error). Each distinct error is logged once until Retry; nothing per frame.
- On the widget, also dev mode only: a GPU placeholder shows the client's reason (program errors always show), and a
  "Dev:" line shows the last other client error.
- An fps badge (bottom-start) on every animating canvas, 2D, live GPU and `wgsl`: frames presented in the last second
  (`AgentWidgetFps.kt`), counted where a frame reaches the screen (the 2D draw of a new revision, a GPU canvas's
  `onSurfaceTextureUpdated`). Hidden while nothing is presented; outside dev mode there's no meter at all.
- Read it live: `adb logcat -G 16M` (a phone's main buffer holds only a few minutes), then
  `adb logcat -s OpenCode/widget`.

## Server seam: the `opencode.widget` plugin

A widget is the `widget` tool call (`{ widgetTemplate, data, title?, wait? }`), found by `(messageID, callID)`.
`:server` talks to the plugin over `POST /api/rpc/opencode.widget/<method>?location[directory]=…`
(`live/LiveWidgets.kt`, `AgentWidgetClient`): `program` (pinned versions cached on disk forever),
`states`, `respond`, `send`, `report`; RPC errors keep their `type` (`OpenCodeClientError.rpcErrorType`: `closed`,
`rate_limited`, `not_found`, `rpc.unavailable`). `rpc.opencode.widget.changed` becomes
`OpenCodeServerEvent.WidgetChanged`, which Home forwards to the session screen.

- `SessionFeature` reads `states` when the session opens, resumes or reconnects
  (`readWidgetStates`) and applies `WidgetChanged`; `State.widgetStates` goes to the transcript as
  `TranscriptConfiguration.widgets` through `AgentWidgetAdapter.states(items, records)`. The pre-plugin
  `widget` message type and REST routes are gone (servers from 877be1f02 on).
- `widgets/AgentWidgetAdapter.kt` pairs each `widget` tool call with its plugin state (else the call
  alone: `metadata.widgetTemplate`, or the latest version, open) and routes answers. Rows, runtime and
  renderer see only `AgentWidgetState` and `AgentWidgetTransport`.
- `send` over 10 a second fails `rate_limited` and is dropped; `closed` shows "This widget isn't open
  any more." / "already answered".
- Synthetic answer, send and error messages carry `metadata.widget { messageID, callID, kind }` and show
  as the quiet `◈` line.
- The server never runs widget code, so failures come from the client: `AgentWidgetController.reportError`
  sends `report { error: { phase, message, line?, column? }, client: "android" }` once per distinct
  phase and message, at most 8 per widget, never once answered. Phases: `init`/`event` (step throws),
  `view` (invalid view, step or live post), `draw` (canvas draw throws; budget overruns don't count),
  `main` (live exit), `shader` (WGSL translation or GL compile), `fetch` (transfer failures, not
  cancels), plus a program that doesn't compile (`init` for step, `main` for live, as the TUI does). Program
  lines subtract the prelude before the widget template source (`sourceLineOffset`, from
  `program`'s `source`, kept in the disk cache); shader lines are already the widget template WGSL's. The agent
  gets a synthetic `kind: "error"` message and can fix and re-post.
- `rpc.unavailable` means the plugin isn't loaded yet (a cold server never waits for plugins; ~0.5 s),
  never "no widgets": `program`, `respond` and `send` try again a few times (0.75 s, 1.5 s, …); a failed
  `states` read marks the session (`widgetStatesUnread`) and is read again on the location's
  `plugin.updated` (`OpenCodeServerEvent.PluginsUpdated`, forwarded by Home), the next transcript refresh or a
  `changed` event.

## Full screen (`specs/v2/widgets-fullscreen.md`)

- Offered (expand button, top right, always shown) while the widget is open and its root's
  `fullscreen` is true, or absent and the view has a `canvas`, a stack's included (`AgentWidgetController.offersFullscreen`).
- `AgentWidgetStore.fullscreen` holds the one widget open full screen; `AgentWidgetFullscreenHost`
  (mounted in `TranscriptList`, outside the lazy list) shows it in a `Dialog`: status bar hidden,
  `SCREEN_ORIENTATION_FULL_USER` while open, a title bar with Close, swipe down (nested-scroll overscroll
  or a drag where nothing scrolls) and system back exit; it closes itself when the widget closes. The inline
  row shows a placeholder at its last height. Same controller: state carries on, canvas/shader clocks
  live in the controller (`startNanos`).
- Entering/leaving sends `{ id: "", type: "fullscreen", value }`; draw frames carry `fullscreen`;
  `LocalAgentWidgetFullscreen` makes `fill` canvases/shaders take the height left (their `height` is the
  minimum), else the view scrolls.
- Live mode will send the same event through `widget.event()` and `Frame.fullscreen` once it exists.

## The runner build

`scripts/psychoscript.sh` pins psychoscript's rev (`REV=`), fetches it into `.psychoscript/` and builds it
into `vendor/psychoscript/` (both gitignored); `:feature:transcript` adds `vendor/psychoscript/jniLibs` to
every variant's jniLibs (`androidComponents`), so no `.so` is committed. The generated Kotlin bindings are
committed (`feature/transcript/src/main/kotlin/com/anomalyco/psychoscript/`) so builds without Rust (CI)
compile; the script refreshes them and says so when they change. A build without the libraries runs, and
widgets show "The widget runtime isn't available".

- Needs: NDK r28+ (`ANDROID_NDK_HOME`, else the newest under the SDK's `ndk/`), `cargo install cargo-ndk`,
  `rustup target add --toolchain 1.100.0 aarch64-linux-android x86_64-linux-android`. About 2 minutes cold.
- `scripts/psychoscript.sh --prebuilt <dir>` takes a build of the same rev made elsewhere (checked against
  its `REVISION`).
- To move the pin: change `REV`, run the script, commit the script and any binding changes together.

## Runner pin

`scripts/psychoscript.sh` pins psychoscript-mobile `900dc23` (branch `mobile`): live slice budgets count only VM
compute (the `next({ timeout })` → `sleep` SliceBudget stop is gone; `wait-probe` finishes), and dev-mode local
network. `fetch` connects only to `FetchRequest.addresses` (resolved and checked once by the runner; OkHttp `Dns`
returns them), and `LiveOptions.allowLocalNetwork` is `AgentWidgetCapabilities.allowsLocalNetwork` (a debug build
or Developer Mode). Cleartext `http:` still needs the debug network security config (10.0.2.2 and localhost only).

## Workflow (Mac)

- JDK: Android Studio's JBR (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`);
  SDK `~/Library/Android/sdk` (cmdline-tools and NDK 29 installed there).
- Dev server: Enter a Server Manually → `http://10.0.2.2:4097`, user `opencode`, password from
  `oc-server.log`. Session lab: `python3 -B …/opencode-ios/scripts/session-lab.py --port 4112
  serve --checkpoint widgets`, connect to `http://10.0.2.2:4112`.

## Handlers, hooks and input (server `a405b4f066`/`deb8e2315a` on)

- Widget templates attach handlers to nodes (`onPress`, `onChange`, `onPointer`) and the prelude assigns node ids from
  paths (`0/:board`); the client sends `{ id, type, value }` for those ids and never interprets them. Live
  `widget.event()`/`next()` are gone. Nothing changed in the node reader; the app's coalescing of queued step events
  now only replaces `change` values and pointer moves (a `down`/`up` pair or keys never collapse).
- Events that belong to no node use `id: ""`: `fullscreen` (now for step widget templates too, their `onFullscreen` hook),
  `focus` and `key` (`AgentWidgetKeys.kt`, hardware keyboard only), `capture` (never sent: no pointer lock on a phone).
- Shaders with an id send `pointer` like canvases (`agentWidgetPointer`).
- Report phases: `init`, `event` (a handler or hook, was `update`), `view`, `draw`, `main`, `shader`, `fetch`.

## Live widgets (`specs/v2/widgets-live.md`)

- A widget template's `mode` comes with its program (`OpenCodeWidgetProgram.isLive`, cached with it on disk);
  `AgentWidgetPrograms` compiles it with `\nreturn await __widget_live(__input())` (`compileLive`).
- `AgentWidgetLiveDriver` owns one run per open widget (`Program.startLive`, input `{ data }`): started when
  the widget first shows, `pause()` while it's off screen, the list isn't resumed (background) and it isn't
  full screen, `resume()` (the program gets `{ type: "resumed", pausedMs }`), and after 30 s paused
  `snapshot()` (a failed snapshot, e.g. a fetch in flight, stops the run and it restarts from `main` when shown).
- Posts are read on the run thread and applied on the main thread: `render` replaces the view (and its root
  `fullscreen`), `draw` replaces a canvas's commands (decoded off the main thread, 2,000-command cap),
  `frame` asks for one frame (delivered at the next vsync while shown, with the first live canvas's size and
  pointer, `dark` and `fullscreen`), `submit`/`dismiss` stop the run and answer through RPC `respond`,
  `send` goes through RPC `send`. Events (taps, changes, canvas pointers, fullscreen) go in as
  `{ type: "event", event }`. A failed run shows its error with Restart.
- Fetch: `AgentWidgetFetchTransport` (OkHttp, no redirects/cookies/cache, DNS filtered with
  `isPublicAddress`); the runner enforces the rules. `AgentWidgetCapabilities.grantedHosts` is the one grant
  decision (everything declared is granted for now); granted hosts show as "Network: …" under the widget.
  Hosts come from the widget state's `capabilities`, else the program's.
- Live widgets offer full screen unless their root says `fullscreen: false`.

## Open


- psychoscript-mobile `900dc23` gives no line or column for a thrown error (`Runtime`, `ExecutionFailure`), so
  reports of `throw` in update/main carry none; syntax errors do.
- Translations were made with `--model opencode/deepseek-v4-pro --review-model opencode/deepseek-v4-pro` (the
  default `opencode/gpt-6.1-sol` was unavailable); a maintainer may want a pass with the default models.
- No `@Preview`s for widgets yet; `AgentWidgetTests` covers the readers, machine and tool call.
