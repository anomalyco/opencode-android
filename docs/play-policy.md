# Play policy checklist for widgets

Widgets run programs the app downloads. The Android app can stay on Google Play only while those programs stay
inside an interpreter. The rules, their sources and the cross-host to-dos (consent, report and block, index, age gate)
are in the spec, [`specs/v2/widgets-store-policy.md`](https://github.com/sst/opencode/blob/widgets/specs/v2/widgets-store-policy.md)
(opencode `widgets`, c6d7f1298 or later). This page covers what the spec means for this repo; it doesn't repeat it.

## The exemption we rely on

> [Google Play, Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16273414): "an
> app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. This
> restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect
> access to Android APIs (such as JavaScript in a webview or browser)." (checked 2026-11-23)

Here the interpreter is PsychoScript's `psychoscript-mobile`, in opencode's `widget-mobile` library. Widget programs
run as bytecode in its VM (`NativeAgentWidgetEngine`),
and reach the app only through the spec's fixed host functions. They render as Compose views, checked 2D draw commands
and GPU batches the runtime checks and replays. Its native library ships inside the APK (`scripts/psychoscript.sh`
pins and vendors it); nothing is downloaded at run time.

## Never add to the Android app

- `REQUEST_INSTALL_PACKAGES`, or any other way to install packages. Libraries can add permissions, so check the
  merged manifest (`app/build/intermediates/merged_manifests/`), not only `AndroidManifest.xml`.
- Native code, dex or JAR from anywhere but the APK: no `System.load`, `DexClassLoader` or `InMemoryDexClassLoader`
  on downloaded bytes.
- A JIT, or any native code generated from widget source.
- Host functions that pass an Android API through by name, and web views or browser engines as a widget runtime.

Any one of these takes widgets out of the exemption. Check this list when a change touches the manifest,
`vendor/psychoscript`, `scripts/psychoscript.sh`, or the engine and host functions in `feature/transcript/…/widgets/`.

## Play Console declarations (App content)

- **Data safety:** answer for what the app sends to the user's OpenCode server, and for widget `fetch`, which goes
  from the device to the hosts the widget template declares, with whatever its program sends.
- **Content rating questionnaire:** widgets show content the agent generates and, once sharing ships, content other
  users made. Answer the user-generated and user-interaction questions with that in mind.
- **App access:** reviewers need a reachable OpenCode server with credentials, plus steps to open a session with a live
  widget and a GPU canvas.
- **Foreground service permissions:** `FOREGROUND_SERVICE_MICROPHONE` (voice calls) needs this declaration. Widgets add
  no permissions.
- Not a declaration, but required by Play policy: in-app reporting for AI-generated content, and moderation for content
  users share (the spec's to-do (b)).

## Android developer verification

> [Android developer verification](https://developer.android.com/developer-verification/guides): "Android requires all
> apps to be registered by verified developers for users to install them on certified Android devices." and "we will
> expand these protections globally for all apps on certified Android devices in 2027." (checked 2026-11-23)

The guide dates the first phase to September 30, 2026 (Brazil, Indonesia, Singapore, Thailand).

- [ ] `ai.opencode.mobilecode` (Play): Play registers a new app's package name when it's created, and Play App Signing
  holds the key. Confirm it on the Play Console home page.
- [ ] `ai.opencode.mobilecode.debug` (the sideloaded `dev-latest` APK): register the package and its signing key in
  Play Console, which also registers apps distributed outside Play, before 2027. CI signs this APK with the runner's
  generated debug key, so it needs a fixed key first: a CI secret, and `.github/` changes need Dax's review
  ([releasing.md](releasing.md)). Unregistered, it installs only through Android's advanced flow for unregistered apps.
