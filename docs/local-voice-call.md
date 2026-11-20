# Local voice call (no voice key)

A call that needs no OpenAI, xAI or Gemini key. The phone listens with Android's
own speech recognition, sends what it heard to the session on screen, and speaks
the reply with a self-hosted OpenAI-compatible speech server (Kokoro).

Everything else in the app — call screen, captions, transcript, call log,
presence notification, haptics, mute, raise-to-ear — is the existing call
surface; only the engine behind it differs.

## Turning it on

1. Run a speech server (see
   [technicalheist/kokoro-voice-server](https://github.com/technicalheist/kokoro-voice-server)).
2. Settings → Voice → Voice Provider → **Speech Server**: enter the base URL,
   e.g. `https://kokoro.example.com`. Leave empty to use the provider's paid API.
3. Leave the voice key empty. Tap the call button.

The local call is chosen automatically in two cases:

- no route is available at all (no key anywhere) **and** a speech server is set;
- the OpenCode server says it can broker voice but answers 5xx (usually "no key
  on the server").

A configured server key or phone key still wins: the local call is the fallback,
not the default.

## Pieces

| File | Role |
| --- | --- |
| `LocalSpeech.kt` | `POST <server>/v1/audio/speech` (model `kokoro`, `wav`), WAV/PCM16 parsing, `AudioTrack` playback, sentence splitting |
| `LocalCallCoordinator.kt` | the turn loop: listen → prompt → reply → speak; Android `SpeechRecognizer` behind `LocalSpeechRecognizer` |
| `VoiceCallHost.kt` | routes a config with `localSessionID` to the local coordinator; mute and hang-up reach it |
| `VoiceFeatureStart.kt` | the fallback rules above; builds the local config |
| `RealtimeProtocol.kt` | `RealtimeSessionConfig.localSessionID` marks a local call |
| `CallVoice.kt` | `synthesizedVoice` maps call voices to Kokoro ids (Marin → `af_heart`) |
| `SharedKeys.kt` | `voiceSpeechServer`, the one stored setting |

## One turn

1. **Listen** — the recognizer runs until one final result. Partial results are
   not used; a pause ends the utterance.
2. **Caption** — `UserTranscript(final = true)`; the call screen shows the words.
3. **Prompt** — `sendPromptConfirming` queues the text on the session on screen.
4. **Reply** — the transcript is polled for a *new* assistant message. The reply
   is only taken once the whole run is over: no tool is running, no message is
   still streaming, and the server no longer lists the session as active, so a
   "let me take a look" said before a tool is not mistaken for the answer.
5. **Speak** — the reply is split into sentences; the first is synthesized
   immediately, two more are fetched in parallel while it plays.
6. **Wait out playback** — playback returns only when the audio has actually
   been heard, so the microphone never reopens over the agent's own voice.
7. Back to step 1.

Turns are sequential and push-to-talk in shape: the microphone is closed while
the agent speaks.

A run that starts a tool or a subagent keeps the call waiting: the interim line
is spoken, and the real reply waits until every step of that run has settled.

## While the agent works

The words go to the session and to the speech server at the same time. The
server runs the same text through its model (`new_generation`) and answers with
a short line, which the app speaks and captions straight away; the agent's real
answer follows when it arrives. A call is not silent while a long task runs.

That line is one quick sentence naming the task back, or a question when the
request is too vague ("Which status would you like me to check?"). Its length
comes from the server's `GENERATION_MAX_CHARS`.

A server without `new_generation` answers without an `X-Generated-Text` header;
the app then says nothing and waits for the agent, exactly as before.

## Audio

- Requests ask for `wav`; the server answers mono 24 kHz PCM16 — the call audio
  format, so nothing is resampled.
- Long replies may come back as a streaming WAV whose data chunk declares no
  length; the parser reads such a chunk to the end of the body.
- Playback uses the media usage, one `AudioTrack` at a time; `stop()` flushes it
  when the call ends or the user hangs up.

## Limits

- No barge-in: you cannot interrupt the agent mid-sentence.
- One prompt in flight; a busy session delays the answer rather than queueing
  several.
- Speech recognition quality follows the phone's engine and language.
- Long answers are limited by how fast the speech server synthesizes.

## Troubleshooting

`adb logcat | grep "OpenCode/voice"` prints one line per stage:

| Line | Meaning |
| --- | --- |
| `Local call heard N characters` | recognition produced a final result |
| `Local call prompt sent` | the prompt reached the session |
| `Local call engagement N characters` | the server's model answered; that line is spoken first |
| `Local call reply N characters` | the session answered |
| `Local speech HTTP 200 N bytes` | the speech server answered |
| `Local speech playing N bytes at 24000 Hz` | audio handed to the phone |
| `Local call engagement failed: …` | the server could not make a line; the call carries on |
| `Local call speech failed: …` | fetch or parse failed |
| `Local call prompt failed: …` | the session rejected the prompt |

If only "heard" appears, the phone never finished the utterance; if only
"prompt sent" appears, the session did not answer (usually busy).

## Tests

```
./gradlew :feature:voice:testDebugUnitTest :feature:settings:testDebugUnitTest
```

`LocalSpeechTests` covers the request shape, key handling, voice mapping and
WAV parsing including unknown-length data.
