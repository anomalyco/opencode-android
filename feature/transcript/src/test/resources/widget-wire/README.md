# Widget binary format fixtures

The committed workloads of psychoscript-mobile (6d49474) (`rust/crates/psychoscript-mobile/tests/fixtures/workloads`),
each in both forms, gzipped:

- `<step>.json` / `<step>.bin`: a step's output from the same session, `RunSession.run` (JSON, with
  `return __widget_step(__input())`) and `RunSession.runBytes` with `ValueEncoder.widget()` (with
  `__widget_emit(__widget_step(__input()))`).
- `<live>.posts.json` / `<live>.posts.bin`: a live run's posts over its first two frames, as `onPosts` gave them
  (a JSON array), and the same values posted again through `startLive(…, encoder: ValueEncoder.widget())`
  (each message as a little-endian u32 length and its bytes). Live programs use `Math.random` and the clock, so the
  bytes come from the JSON values rather than from a second run.

Regenerate with `scripts/widget-wire-fixtures/generate.sh` (it follows `scripts/psychoscript.sh`'s REV). Live
workloads that use randomness or the clock come out different each time; the rest are byte for byte the same.

`AgentWidgetTests` decodes the bytes, checks that its JSON encoder (`AgentWidgetWireEncoder`, a port of the runtime's)
writes the same values from the JSON, and fuzzes the bytes.
