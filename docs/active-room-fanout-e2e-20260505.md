# Active Room Fan-out v1과 브라우저 E2E 검증

## 한 줄 요약

hot-room fan-out에서 모든 WebSocket 연결을 같은 수신자로 보지 않고, 사용자가 실제로 보고 있는 채팅방 세션만 full WebSocket payload 대상으로 남겼다. passive 세션은 full message를 받지 않고, 사용자가 다시 visible 상태로 돌아오면 `/messages/after`로 누락분을 복구한다.

## 문제 정의

OpenChat의 hot-room 모델에서 입력 TPS는 사용자 수에 비례하지만, WebSocket delivery work는 메시지당 방 인원 수만큼 증가한다. 한 방에서 `N`명이 초당 1개씩 메시지를 보내면 입력은 `N msg/s`지만, logical delivery는 대략 `N * N sends/s`가 된다.

1500명 단일방이 안정권이라고 해도 fan-out 기준으로는 초당 약 225만 logical delivery work를 처리하는 형태다. 이 구조에서 서버를 더 늘리는 것만 먼저 선택하면, 실제로 사용자가 보고 있지 않은 세션에도 계속 full payload를 보내는 낭비가 남는다.

그래서 v1에서는 "더 많은 서버"보다 먼저 "full fan-out을 받아야 하는 세션인가"를 구분했다. 목적은 성능 개선 수치를 주장하는 것이 아니라, 불필요한 fan-out 대상을 줄이고 메시지 복구 경로가 깨지지 않는지 검증하는 것이다.

## 설계 의도

서버는 사용자가 현재 어떤 화면을 보고 있는지 직접 알 수 없다. 따라서 Web/모바일 클라이언트가 화면 상태를 WebSocket control message로 알려주고, 서버는 이 신호와 TTL을 조합해 active 여부를 판단한다.

v1의 기준은 단순하다.

- visible 채팅방: full WebSocket payload 수신 대상
- hidden/background/다른 화면: passive 처리
- passive 세션: full message fan-out 제외
- visible 복귀: REST `/messages/after`로 누락 메시지 복구

이 방식은 한 방의 절대 최대 인원을 늘리는 튜닝이 아니다. 같은 리소스 안에서 사용자가 실제로 보고 있지 않은 세션으로 나가는 delivery work를 줄이는 구조 개선이다.

## v1 동작

클라이언트는 다음 control message를 보낸다.

```json
{ "type": "room.active", "roomId": 1, "lastSeenSequence": 123 }
```

```json
{ "type": "room.active.heartbeat", "roomId": 1, "lastSeenSequence": 123 }
```

```json
{ "type": "room.passive", "roomId": 1, "lastSeenSequence": 123 }
```

서버 판단식은 다음과 같다.

```text
active =
  lastDeclaredState is active 계열
  AND now - lastActiveSignalAt <= 60s
```

구체적인 정책은 다음과 같다.

- Web에서는 현재 route가 해당 채팅방이고 `document.visibilityState === "visible"`이면 active로 본다.
- 채팅방 진입이나 visible 복귀 시 `room.active`를 보낸다.
- 보고 있는 동안 `20s`마다 `room.active.heartbeat`를 보낸다.
- 채팅방 이탈, 다른 방 이동, 탭 hidden 시 `room.passive`를 보낸다.
- passive 전송이 실패해도 heartbeat가 끊기면 `60s` TTL 이후 passive로 간주한다.
- WebSocket 연결 직후 기본값은 active다.
- WebSocket 연결의 `roomId`와 payload의 `roomId`가 다르면 저장/ack/publish 경로를 타지 않고 warn 로그만 남긴다.
- v1에서는 passive 세션에 summary event를 보내지 않는다.

사용자가 다시 방을 보면 FE는 `room.active`를 보내고, 마지막으로 본 sequence 이후의 메시지를 다음 REST API로 복구한다.

```text
GET /api/rooms/{roomId}/messages/after?cursor={lastSeenSequence}
```

## 검증

초기 구현 단계에서는 새 클라우드 부하테스트를 돌리지 않고 BE 단위 테스트와 FE 브라우저 E2E로 동작 안정성을 먼저 확인했다. 이후 1500명 단일방 active/passive hot-room 시나리오를 추가로 실행해, 실제 부하에서도 fan-out 대상 축소가 서버 metric과 k6 결과에 반영되는지 확인했다.

BE 단위 테스트에서는 다음을 확인했다.

- 기존 채팅 payload는 계속 chat message로 처리된다.
- `room.active`, `room.active.heartbeat`, `room.passive` control message를 파싱한다.
- control message는 DB 저장, ack, publish 경로를 타지 않는다.
- payload `roomId`가 WebSocket 연결의 `roomId`와 다르면 처리하지 않는다.
- 연결 직후 세션은 active로 시작한다.
- `room.passive` 수신 시 즉시 fan-out 대상에서 제외된다.
- `room.active` 수신 시 다시 fan-out 대상에 포함된다.
- heartbeat는 active TTL을 연장한다.
- TTL이 만료된 세션은 fan-out 대상에서 제외된다.
- passive/TTL 제외 세션 수는 `ws.fanout.passive_omitted`로 기록된다.

FE 브라우저 E2E에서는 사용자 관점의 흐름을 확인했다.

- 채팅방이 visible 상태일 때 `room.active`와 heartbeat가 전송된다.
- 탭이 hidden 상태가 되면 `room.passive`가 전송된다.
- hidden 중 다른 세션이 보낸 메시지는 현재 화면에 즉시 표시되지 않는다.
- 다시 visible 상태로 돌아오면 `/messages/after`가 호출된다.
- REST sync 이후 hidden 중 누락된 메시지가 화면에 복구된다.

검증 근거는 다음 커밋과 명령 기준으로 기록한다.

| 구분 | 근거 |
| --- | --- |
| BE 구현 | PR `#5 perf: add active room fanout controls`, commit `49a495c` |
| BE 설계 메모 | commit `61004c3` |
| FE E2E | FE commit `83ef519 test: add chat e2e coverage` |
| 1500명 active/passive 부하테스트 | `infra/gcp-loadtest/results/2026-05-05-active-passive-hot-room-1500.md` |
| BE 검증 | `./gradlew test` |
| FE 검증 | `npm run e2e:local`, `npm run build`, `npm run lint`, `tsc --noEmit` |

## 1500명 Active/Passive 부하테스트

1500명 단일방에서 active 30%, passive 70% 조건으로 `20260505-211649-hr1500ap-main` run을 실행했다. 구성은 API `e2-standard-4 x1`, Realtime `e2-standard-8 x4`, k6 `e2-standard-8 x2`였고 monitoring VM은 띄우지 않았다.

핵심 결과는 다음과 같다.

- active/passive assigned: `450 / 1050`
- sent / ack / DB rows: `50,870 / 50,870 / 50,870`
- passive unexpected messages: `0`
- ack p95 worst: `23ms`
- visible freshness p95 worst: `117.5ms`
- 서버 `ws_session_max`: active `450`, passive `1050`
- 서버 `ws.fanout.passive_omitted`: `12,778,286`
- 서버 `ws.send.failed`: `0`
- 서버 `ws.broadcast.lane_done.since_created` p95 worst: `158.9ms`

이 결과는 passive 세션이 full payload를 받지 않고, active 사용자의 체감 지연과 DB 정합성이 유지됐다는 근거다.

다만 `logical delivery per DB row` 전체 평균은 steady-state active 수와 1:1로 해석하지 않는다. 이번 시나리오는 120초 ramp 중에도 각 VU가 바로 채팅을 시작하므로, 전체 평균에는 아직 모든 active 세션이 연결되지 않은 구간이 섞인다. steady-state delivery TPS를 정확히 말하려면 ramp 이후 hold 구간을 분리하거나 monitoring on으로 해당 구간만 잘라서 봐야 한다.

## 포트폴리오용 정리

서버를 더 늘리기 전에, 사용자가 실제로 보고 있지 않은 세션으로 나가는 full fan-out을 줄이는 구조를 먼저 적용했다.

성능 개선 수치가 아니라, fan-out 대상 축소와 메시지 복구 안정성을 브라우저 E2E와 1500명 active/passive 부하테스트로 검증했다.

이 작업은 "한 방 최대 인원"보다 "같은 리소스에서 불필요한 delivery work를 줄이고, 사용자가 다시 돌아왔을 때 메시지를 잃지 않는가"에 초점을 둔 개선이다.

## 남은 과제

- passive 세션용 unread count 또는 summary event는 v2에서 별도로 설계한다.
- steady-state delivery TPS가 필요하면 ramp 이후 hold 구간을 분리한 시나리오로 다시 측정한다.
- 여러 hot-room과 작은 방이 동시에 존재할 때 room 단위 fan-out ownership 또는 shard 구조와 함께 검토한다.
