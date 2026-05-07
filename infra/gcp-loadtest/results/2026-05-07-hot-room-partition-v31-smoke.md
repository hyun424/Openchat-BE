# 2026-05-07 Hot Room Partition v3.1 GCP Smoke

## 목적

v3.1 autoscaling-aware partition이 GCP role-split 환경에서 동작하는지 확인했다. 목표는 대규모 부하가 아니라 다음 네 가지였다.

- `/ws-route`가 room partition state를 기준으로 응답하는지
- scale-up 후 신규 접속 route가 늘어난 partition count를 반영하는지
- drain 중인 partition으로 신규 접속이 배정되지 않는지
- active/passive smoke에서 DB 저장 정합성과 passive fan-out 제외가 유지되는지

## 실행 환경

| 항목 | 값 |
| --- | --- |
| run id | `20260507-v31-manual` |
| profile | `hot-room-partition-v31-smoke` |
| GCP project | `openchat-495102` |
| API | `e2-standard-2 x 1` |
| Realtime | `e2-standard-4 x 2` |
| k6 | `e2-standard-4 x 1` |
| Monitoring | off |
| estimated vCPU / SSD | `20 vCPU / 110GB` |
| scenario | `k6/scenarios/10-active-passive-hot-room-ramped.js` |
| VU | `100` |
| active/passive | `30 / 70` |
| chat duration | `300s` |
| cleanup | manual, 검증 후 삭제 |

결과는 GCS에 업로드됐다.

```text
gs://openchat-loadtest-openchat-495102/runs/20260507-v31-manual/
```

## k6 / DB 결과

| 항목 | 결과 |
| --- | ---: |
| k6 exit code | `0` |
| checks | `600 pass / 0 fail` |
| WebSocket connect success | `100%` |
| HTTP error rate | `0%` |
| active assigned | `30` |
| passive assigned | `70` |
| sent | `8,688` |
| ack | `8,688` |
| DB rows | `8,688` |
| passive unexpected messages | `0` |
| ack p95 | `18ms` |
| visible p95 | `114ms` |

DB row count는 다음 prefix로 확인했다.

```text
client_message_id LIKE 'hot-room-partition-v31-smoke-shared-100vu-%'
```

## Partition Operation 결과

수동 검증은 k6 VM에서 내부 LB를 호출해 진행했다.

| 동작 | 관측 결과 |
| --- | --- |
| 초기 `/ws-route` | `partitionCount=2`, `version=1` |
| scale-up | `targetPartitionCount=4`, 응답 accepted |
| scale-up 후 `/ws-route` | `partitionCount=4`, `version=2`, `routeVersion=2` 포함 |
| drain 시작 | `drainingPartitions=[1]`, 응답 accepted |
| drain 후 신규 route 샘플 | `partitionId`가 `0`, `2`, `3`으로만 배정됨 |
| route draining avoided metric | API node에서 `4` 증가 |
| drain complete | `partitionCount=3`, `version=4` |

앱별 Prometheus snapshot에서 partition publish/subscribe/fan-out metric도 확인했다.

| node | 주요 관측 |
| --- | --- |
| API | `openchat_room_partition_scale_event_total{direction="up",result="success"} 1`, `down/draining 1`, `down/success 1` |
| API | `openchat_room_partition_route_draining_avoided_total 4` |
| Realtime 1 | `openchat_room_partition_subscribe_total{mode="partition"} 8688`, `fanout_deliveries_sum 101459` |
| Realtime 2 | `openchat_room_partition_subscribe_total{mode="partition"} 8688`, `fanout_deliveries_sum 151480` |

## 확인된 보완점

`drain/reconnect`를 LB/API 경유로 호출했을 때 응답은 다음과 같았다.

```json
{"roomId":1,"operation":"drain-reconnect","accepted":true,"targetedSessions":0}
```

이 값은 단순한 성공이 아니다. 현재 `RoomSessionRegistry`는 각 app instance의 in-memory 상태이므로, API node에서 internal reconnect endpoint를 실행하면 API node에 붙은 WebSocket session만 조회한다. Realtime node에 실제 session이 있어도 API node registry에서는 보이지 않는다.

따라서 v3.1에서 확인된 결론은 다음과 같다.

- room partition state 기반 route는 동작한다.
- scale-up 후 신규 접속 route는 증가한 partition count를 반영한다.
- drain partition은 신규 접속 후보에서 제외된다.
- active/passive smoke와 DB 저장 정합성은 유지된다.
- reconnect control은 API node 단독 호출로는 운영 동작이 완성되지 않는다.

## 다음 작업

다음 보강은 `room.reconnect` 명령을 Realtime owner에게 전달하는 control-plane이다.

우선순위는 Redis control channel 방식이 적합하다. v3.2에서는 이 방향을 적용한다.

- API/internal operation endpoint는 DB state를 변경한다.
- API는 `openchat:room-partition-control:{roomId}` control channel에 reconnect command를 publish한다.
- 각 Realtime node는 control command를 받아 자기 `RoomSessionRegistry`에서 draining partition session만 찾아 `room.reconnect`를 전송한다.
- metric은 API command accepted와 Realtime targeted/sent를 분리해서 기록한다.

이 보강 후에 다시 작은 GCP smoke로 `targetedSessions > 0`과 클라이언트 reconnect 수신을 확인한다.

v3.2 실제 channel 이름은 chat payload와 구분하기 위해 다음으로 고정했다.

```text
openchat:room-partition-control:{roomId}
```

API 응답은 더 이상 `targetedSessions`를 반환하지 않고 `publishedCommands`를 반환한다. 실제 세션 target/sent 수는 Realtime node의 `openchat_room_reconnect_sessions_targeted_total`, `openchat_room_reconnect_control_sent_total` metric으로 확인한다.

## 리소스 정리

검증 후 다음 리소스는 삭제했다.

- k6/API/Realtime/LB/MySQL/Redis VM
- run 전용 NAT/router/firewall/subnet/network
- run 전용 service account/IAM binding/source object
- IAP SSH 임시 firewall

Terraform state에는 결과 bucket, Grafana static IP, archive data만 남겼다.
