# 2026-05-07 Hot Room Partition v3.2 Control Plane GCP Smoke

## 목적

v3.1 smoke에서 확인된 `targetedSessions=0` 문제를 v3.2 Redis control-plane으로 해결했는지 확인했다.

이번 테스트는 부하 성능 측정이 아니다. 목표는 API node가 internal operation을 받아 Redis control command를 publish하고, 실제 WebSocket session을 가진 Realtime node가 command를 수신해 `room.reconnect` control payload를 보내는지 확인하는 것이다.

## 실행 환경

| 항목 | 값 |
| --- | --- |
| final run id | `v32-r3-control-smoke` |
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
gs://openchat-loadtest-openchat-495102/runs/v32-r3-control-smoke/
```

## 재시도 기록

처음 두 번은 의도적으로 실패 원인을 좁히는 smoke가 됐다.

| run id | 결과 | 원인 |
| --- | --- | --- |
| `20260507-v32-control-smoke` | `publishedCommands=0`, `publish_failed=1` | Redis control publisher 대신 noop publisher가 선택됨 |
| `20260507-v32-control-smoke-r2` | 동일 실패 | `@Primary`만으로는 부족했고, Redis publisher bean 자체가 조건부 생성에서 빠짐 |
| `v32-r3-control-smoke` | 성공 | Redis control publisher를 unconditional primary bean으로 고정하고, noop publisher를 Spring bean에서 제외 |

문제의 핵심은 `RoomPartitionControlPublisher` 구현체 선택이었다. API node에는 Redis 연결이 정상적으로 있었지만, 조건부 bean 평가 순서 때문에 실제 publish 구현체가 아니라 noop 구현체가 주입됐다. 그래서 v3.2 command publish가 실패 metric으로만 기록됐다.

최종 수정은 다음 방향으로 정리했다.

- `RedisRoomPartitionControlPublisher`는 항상 primary Spring bean으로 등록한다.
- `NoopRoomPartitionControlPublisher`는 테스트/확장용 plain class로 두고 Spring component에서 제외한다.
- Redis 장애 시에는 실제 publisher가 예외를 잡아 `publish_failed` metric과 warn log를 남긴다.

## 수동 Control Plane 검증

k6가 shared room `roomId=1`로 100 VU smoke를 실행 중일 때, k6 VM에서 내부 LB를 호출했다.

| 동작 | 관측 결과 |
| --- | --- |
| 초기 `/ws-route` | `partitionCount=2`, `version=1` |
| scale-up | `targetPartitionCount=4`, 응답 accepted |
| scale-up 후 `/ws-route` | `partitionCount=4`, `version=2`, `routeVersion=2` |
| drain 시작 | `drainingPartitions=[1]`, 응답 accepted |
| drain reconnect | `accepted=true`, `publishedCommands=1` |

API 응답은 v3.1의 `targetedSessions`가 아니라 v3.2의 `publishedCommands` 기준으로 바뀌었다.

```json
{"roomId":1,"operation":"drain-reconnect","accepted":true,"publishedCommands":1}
```

## Prometheus Metric

최종 smoke에서 API node는 Redis control command publish 성공을 기록했다.

```text
openchat_room_partition_control_publish_total{result="success",type="partition.reconnect"} 1
openchat_room_reconnect_requested_total{reason="scale_down"} 1
```

Realtime node 두 대는 모두 control command를 수신했다.

```text
openchat_room_partition_control_received_total{result="success",type="partition.reconnect"} 1
```

실제 draining partition session을 가진 Realtime node는 51개 session에 reconnect control을 전송했다.

```text
openchat_room_reconnect_sessions_targeted_total{reason="scale_down"} 51
openchat_room_reconnect_control_sent_total{reason="scale_down",result="success"} 51
```

따라서 v3.1의 한계였던 “API node local registry에서는 Realtime node session이 보이지 않는다” 문제는 v3.2에서 해결됐다. API는 명령을 publish하고, Realtime node는 자기 node-local session registry에서 대상 세션을 찾아 실행한다.

## k6 / DB 결과

| 항목 | 결과 |
| --- | ---: |
| k6 exit code | `0` |
| checks | `600 pass / 0 fail` |
| WebSocket connect success | `100%` |
| HTTP error rate | `0%` |
| active assigned | `30` |
| passive assigned | `70` |
| sent | `8,686` |
| ack | `8,686` |
| DB rows | `8,686` |
| passive unexpected messages | `0` |
| ack p95 / p99 | `20ms / 26ms` |
| visible p95 / p99 | `134ms / 138.34ms` |
| handler duration p95 | `0ms` |
| json parse duration p95 | `1ms` |

DB row count는 다음 prefix로 확인했다.

```text
client_message_id LIKE 'hot-room-partition-v31-smoke-shared-100vu-%'
```

## 결론

v3.2 Redis control-plane smoke는 성공이다.

- API node는 `partition.reconnect` command를 Redis control channel에 publish했다.
- Realtime node들은 chat payload와 분리된 control subscriber로 command를 수신했다.
- 실제 session owner Realtime node가 draining partition의 WebSocket session 51개에 `room.reconnect`를 전송했다.
- 기존 active/passive smoke의 기본 정합성도 유지됐다.
  - connect success `100%`
  - HTTP error `0%`
  - DB rows = ack count = `8,686`
  - passive unexpected messages `0`

이번 결과의 포트폴리오 포인트는 다음이다.

> WebSocket 세션이 node-local이라는 제약을 GCP smoke에서 확인하고, API 명령과 실제 세션 이동 실행을 Redis control-plane으로 분리했다.

## 남은 범위

v3.2는 control command 전달까지만 검증했다. 다음 작업은 클라이언트가 `room.reconnect`를 받았을 때 조용히 새 route로 재접속하고 `/messages/after`로 복구하는 FE/브라우저 E2E다.

대규모 1500명급 부하테스트는 FE reconnect와 least-loaded route 보강 이후로 미룬다.

## 리소스 정리

검증 후 다음 리소스는 삭제했다.

- k6/API/Realtime/LB/MySQL/Redis VM
- run 전용 NAT/router/firewall/subnet/network
- run 전용 service account/IAM binding/source object
- IAP SSH 임시 firewall

Terraform state에는 결과 bucket, Grafana static IP, archive data만 남겼다.
