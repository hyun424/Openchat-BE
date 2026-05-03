# Room Sequence + WebSocket Batch Payload Experiment

## Summary

- Branch: `perf-room-sequence-batch-envelope`
- Base commit: `51970f5`
- Experiment commit: `428ee5a`
- Goal: reduce WebSocket frame count by sending a room-local batch envelope when multiple messages arrive inside a short window.
- Result: frame count dropped sharply, and RTT improved, but 500-user hot-room p95 latency is still seconds-level.

## Code Changes

- Added `sequence` alias to `ChatMessageDto`.
  - `sequence` is backed by existing `Message.id`.
  - No `room_sequence` column was added.
- Added `ChatBatchMessageDto`.
  - Batch payload type: `chat.batch`
  - Contains `roomId`, `firstSequence`, `lastSequence`, and `messages`.
- Updated `ChatFanoutService`.
  - Dedupe still happens first by `messageId`.
  - Same-room messages are buffered for `app.websocket.batch.window-ms=20`.
  - Flush behavior:
    - 1 message: existing single-message payload
    - 2+ messages: one batch envelope
  - Default max batch size: `64`.
- Updated `ChatOutboundSender` and `WebSocketOutboundSender`.
  - Default batch implementation falls back to single-message sends.
  - WebSocket implementation sends batch envelopes through `RoomSessionRegistry`.
- Updated `RoomSessionRegistry`.
  - `sendBatchToRoom(...)` serializes one batch envelope and sends the same `TextMessage` to all room sessions.
- Added sync API:
  - `GET /api/rooms/{roomId}/messages/after?cursor=<messageId>&limit=500`
  - Uses `Message.id > cursor` and returns `id ASC`.
- Updated k6 WebSocket parser.
  - `ws_frames_received_total`: physical WebSocket frames
  - `ws_messages_received_total`: logical chat messages
  - RTT, duplicate, and unique calculations now inspect messages inside `chat.batch`.

## Verification

- `./gradlew test`: passed
- `terraform -chdir=infra/gcp-loadtest validate`: passed outside sandbox
- GCP run:
  - run id: `20260503-0957-sequence-batch-500`
  - profile: `target-500-ramped`
  - VUs: `500`
  - connect ramp: `60s`
  - chat duration: `120s`
  - send interval: `1000ms`
  - app shape: `4 x e2-standard-8`
  - total estimated vCPU: `58`

## GCP Result

| Metric | Baseline | Batch envelope |
| --- | ---: | ---: |
| WS connect success | 100% | 100% |
| HTTP error rate | 0% | 0% |
| RTT p50 | 13.30s | 7.49s |
| RTT p95 | 33.06s | 26.68s |
| RTT p99 | 34.46s | 32.10s |
| WS messages sent | 59,664 | 59,733 |
| Logical messages received | 19,761,526 | 21,662,040 |
| Physical frames received | not measured | 2,147,935 |

Frame ratio in the batch-envelope run:

```text
ws_frames_received_total / ws_messages_received_total = 2,147,935 / 21,662,040 = 9.92%
logical messages per frame ~= 10.09
```

## Interpretation

The experiment worked mechanically: clients received batch payloads, k6 counted logical messages inside the batch, and physical WebSocket frames dropped to about one tenth of logical messages.

Latency improved but not enough:

- p50 improved by about `44%`.
- p95 improved by about `19%`.
- p99 improved by about `7%`.
- The run still failed the `p95 < 100ms` threshold.

This means frame count is a real cost, but it is not the only dominant bottleneck. The system still accumulates multi-second backlog under a 500-user hot-room broadcast.

## Notable Signals

- k6 exit code: `99`
  - Reason: `ws_message_roundtrip_ms` threshold failed.
- App metrics show DB pool pressure during the run:
  - `hikaricp_connections_acquire_seconds_max` reached about `5s` on app 2 and app 3.
  - `hikaricp_connections_timeout_total` was `1` on app 2 and app 3.
- This suggests the next experiments should not only tune WebSocket send. The message ingest DB path is also applying pressure during hot-room traffic.

## Conclusion

Keep this as a useful partial improvement, but do not treat it as the final 500-user solution. The next useful direction is to separate durability from real-time broadcast more explicitly:

- reduce synchronous DB work on the message send path, or move to append-log style persistence;
- keep batch envelopes, because they reduce wire/frame overhead;
- add precise stage metrics around DB save, Redis publish/subscribe, batch wait, batch flush, and socket send completion.
