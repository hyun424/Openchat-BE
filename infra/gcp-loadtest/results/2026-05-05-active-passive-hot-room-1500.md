# 1500명 Active/Passive Hot Room 측정 결과

## 요약

1500명 단일방 조건에서 전체 사용자를 모두 active로 보지 않고, 실제로 방을 보고 있는 active 세션 30%만 full WebSocket payload 대상으로 남기는 시나리오를 측정했다.

결과적으로 1500명 연결은 유지됐고, active/passive 배정은 목표 비율과 일치했다. passive 세션은 full payload를 받지 않았고, active sender의 ack와 observer visible freshness도 기준 안에 있었다. DB row count와 ack count도 일치했다.

이번 결과는 "최대 인원 증가"가 아니라, 같은 방 인원 1500명에서 full fan-out 대상이 active 세션으로 제한되는지 확인한 것이다.

## 실행 조건

| 항목 | 값 |
| --- | --- |
| Run ID | `20260505-211649-hr1500ap-main` |
| Profile | `hot-room-1500-active-passive` |
| Scenario | `k6/scenarios/10-active-passive-hot-room-ramped.js` |
| Room | shared room 1개 |
| VUs | `1500` |
| k6 workers | `2`, worker당 `750` VU |
| active/passive | active `30%`, passive `70%` |
| active role | sender `94%`, observer `5%`, validator `1%` |
| connect ramp | `120s` |
| chat duration | `120s` |
| send interval | `1000ms` |
| monitoring | off |
| 앱 리소스 | API `e2-standard-4 x1`, Realtime `e2-standard-8 x4` |
| k6 리소스 | `e2-standard-8 x2` |
| 예상 리소스 | `62 vCPU`, SSD `195GB` |

## Smoke

수정된 스모크 run은 `20260505-210458-hr1500ap-smoke2`다.

| 항목 | 결과 |
| --- | ---: |
| VUs | `100` |
| active/passive assigned | worker별 `15 / 35`, 총 `30 / 70` |
| sent / ack / DB rows | `849 / 849 / 849` |
| passive unexpected | `0` |
| worker exit code | `0 / 0` |

스모크에서 두 worker가 같은 shared room을 사용했고, active/passive control message와 DB 정합성이 정상임을 확인했다.

## Main 결과

| 항목 | worker-1 | worker-2 | 합계/최악 |
| --- | ---: | ---: | ---: |
| WebSocket connect success | `100%` | `100%` | `100%` |
| HTTP error rate | `0%` | `0%` | `0%` |
| active assigned | `225` | `225` | `450` |
| passive assigned | `525` | `525` | `1050` |
| sent | `25,317` | `25,553` | `50,870` |
| ack | `25,317` | `25,553` | `50,870` |
| DB rows | - | - | `50,870` |
| passive unexpected | `0` | `0` | `0` |
| ack p95 / p99 | `21ms / 27ms` | `23ms / 32ms` | `23ms / 32ms` |
| visible p95 / p99 | `117.5ms / 129ms` | `91ms / 104.2ms` | `117.5ms / 129ms` |
| handler p95 | `1ms` | `1ms` | `1ms` |
| JSON parse p95 | `1ms` | `1ms` | `1ms` |

## Server Snapshot

Monitoring VM 없이 실행했기 때문에 Grafana time-series가 아니라 app `/actuator/prometheus` before/after snapshot 기준으로 해석한다.

| 서버 지표 | 값 |
| --- | ---: |
| `ws_session_max{type="active"}` 합계 | `450` |
| `ws_session_max{type="passive"}` 합계 | `1050` |
| `ws_fanout_max{type="active_sessions"}` 합계 | `450` |
| `ws.fanout.passive_omitted` | `12,778,286` |
| `ws.send.succeeded` | `5,471,377` |
| `ws.send.frame.succeeded` | `336,515` |
| `ws.send.failed` | `0` |
| `ws.send.bytes` | `1,796,230,178 bytes` |
| `ws.broadcast.lane_done.since_created` p95 worst | `158.9ms` |
| `ws.broadcast.lane_done.since_created` max worst | `218ms` |
| `ws.send.duration` p95 worst | `0.188ms` |
| `ws.send.duration` max worst | `10.609ms` |

`ws_session_max`와 `ws_fanout.active_sessions`가 전체 1500이 아니라 active 450 근처로 잡혔고, passive 세션 제외 카운터가 지속 증가했다. k6의 `ws_passive_unexpected_messages_total`은 0이므로 passive 세션에 full payload가 새지 않았다.

## 해석 주의점

`logical delivery per DB row`를 전체 run 평균으로 계산하면 `5,471,377 / 50,870 = 107.6`으로 나온다. 이 값을 steady-state active 수 450으로 해석하면 안 된다.

이 시나리오는 `connect_ramp_seconds=120` 동안 VU가 점진적으로 들어오면서 바로 채팅을 시작한다. 따라서 전체 run 평균에는 아직 모든 active 세션이 연결되지 않은 ramp 구간의 메시지가 섞인다. 이번 실행에서 steady-state에 가까운 판정은 전체 평균 delivery per row보다 다음 지표를 우선한다.

- active/passive assigned: `450 / 1050`
- 서버 session max: active `450`, passive `1050`
- fan-out 대상 max: active sessions `450`
- passive omitted 증가: `12,778,286`
- passive unexpected: `0`
- active 사용자 지연: visible p95 worst `117.5ms`, ack p95 worst `23ms`

steady-state delivery TPS를 정확히 말하려면 ramp 이후 모든 VU가 연결된 상태에서 별도 hold 구간을 두거나, monitoring on으로 time-series 구간을 잘라서 봐야 한다.

## 결론

1500명 단일방에서 active 30%, passive 70%로 나눈 실제 서비스형 수신 모델은 정상 동작했다.

- 전체 1500명 연결은 성공했다.
- active/passive 비율은 의도대로 배정됐다.
- passive 세션은 full payload를 받지 않았다.
- active 사용자의 ack/visible 지연은 기준 안에 있었다.
- DB 저장 정합성은 유지됐다.
- 서버 send 실패는 0이었다.

이번 결과는 "성능이 좋아졌다"는 수치 홍보가 아니라, Active Room Fan-out v1이 같은 방 인원에서 full fan-out 대상을 active 세션으로 줄이고 메시지 정합성을 유지한다는 검증 결과로 기록한다.

## 다음 단계

- steady-state delivery TPS가 필요하면 ramp 이후 hold 구간이 분리된 시나리오를 추가한다.
- active/passive가 섞인 `1500 hot room + medium/small rooms` 멀티룸 시나리오를 별도로 설계한다.
- passive summary event, unread count, room fan-out ownership은 v2 설계로 분리한다.
