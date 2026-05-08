# Mixed-room Workload Observer Smoke

작성일: 2026-05-07

## Summary

`RoomTrafficMonitor`에 `actualDeliveryWorkPerSecond`, `conceptualRoomWorkPerSecond`, `scaleDecisionWorkPerSecond`를 분리한 뒤 GCP small smoke로 mixed-room workload 관측이 실제로 동작하는지 확인했다.

이번 실행의 목적은 최대 인원이나 TPS 홍보가 아니라, **여러 크기의 방이 섞인 상황에서 room workload observer가 기대한 값을 남기는지** 확인하는 것이다. 자동 rebalance나 scale-up은 실행하지 않았다.

## Run

| 항목 | 값 |
| --- | --- |
| run id | `20260507-mixed5-workload-smoke` |
| scenario | `k6/scenarios/11-mixed-room-workload-ramped.js` |
| profile | `infra/gcp-loadtest/profiles/mixed-room-workload-smoke.tfvars.example` |
| VU | `100` |
| shape | hot `1 x 40`, medium `3 x 15`, small `5 x 3` |
| active/passive | scenario 기본값 사용 |
| API | `e2-standard-2 x 1` |
| Realtime | `e2-standard-4 x 2` |
| LB/MySQL/Redis | `e2-standard-2` 계열 |
| k6 | `e2-standard-4 x 1` |
| monitoring | off |
| cleanup | 완료 |
| GCS | `gs://openchat-loadtest-openchat-495102/runs/20260507-mixed5-workload-smoke/` |

## Result

| 항목 | 결과 |
| --- | ---: |
| k6 exit code | `0` |
| WebSocket connect success | `100%` |
| WebSocket connect failure | `0%` |
| HTTP error rate | `0%` |
| sent | `1,304` |
| ack | `1,304` |
| DB rows | `1,304` |
| visible p95 | `37ms` |
| visible p99 | `37ms` |
| passive unexpected messages | `0` |
| assigned users | `100` |
| config mismatch | `0` |

DB row count와 ack count가 일치했고, passive 세션이 full payload를 받은 신호도 없었다. 따라서 이번 smoke에서는 active/passive, shared room 생성, room route 조회, 메시지 저장/ack 경로가 정상적으로 유지됐다.

## Workload Observer Metrics

Prometheus after snapshot 기준 Realtime node별 workload max 값은 다음과 같다.

| node | existing room work max | actual delivery work max | conceptual work max | scale decision work max | recommended partitions max | effective partitions max | limited count |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| app-2 | `386/s` | `386/s` | `195/s` | `386/s` | `1` | `1` | `0` |
| app-3 | `420/s` | `420/s` | `210/s` | `420/s` | `1` | `1` | `0` |

`app-1`과 `lb`는 Realtime delivery owner가 아니므로 workload 계열 값이 `0`으로 남는 것이 정상이다.

## Interpretation

이번 결과는 다음을 확인한다.

- 기존 `openchat_room_work_max_per_second`는 실제 delivery 관측치인 `actualDeliveryWorkPerSecond`와 같은 의미로 유지됐다.
- 새로 추가한 `conceptualRoomWorkPerSecond = inboundMessagesPerSecond * activeSessions`도 별도 metric으로 확인됐다.
- `scaleDecisionWorkPerSecond = max(actual, conceptual)`가 snapshot에 남았다.
- `recommendedPartitions > effectivePartitions`인 cap limited room은 없었다.
- mixed-room smoke 수준에서는 partition 증설 추천이 필요하지 않았다.

즉, 이번 변경은 scale 정책을 바꾼 것이 아니라 **판단에 필요한 관측값을 분리해서 볼 수 있게 만든 작업**이다.

## Issues Found And Fixed

테스트 과정에서 다음 문제가 확인되어 수정했다.

| 문제 | 원인 | 조치 |
| --- | --- | --- |
| `/ws-route` 409 | room enter 직후 route 조회가 먼저 수행됨 | k6에서 room enter 후 route retry 처리 |
| MySQL `GET_LOCK` 실패 | advisory lock key가 64자 제한을 초과 | userId를 SHA-256 기반 짧은 key로 변환 |
| `user_id` column too long | loadtest user id가 DB 컬럼보다 김 | mixed scenario user id를 짧게 생성 |
| 기존 room work max가 after snapshot에서 `0` | rate window 만료 후 max gauge가 현재값으로 덮임 | max gauge를 peak 유지 방식으로 수정 |

## Cleanup

k6 cleanup과 Terraform destroy가 일부 겹치면서 이미 삭제된 Redis VM이 Terraform state에 남아 있었다. 해당 resource만 state에서 제거한 뒤 남은 네트워크/NAT/router/service account 리소스를 destroy했다.

최종 확인 결과:

- `20260507-mixed5-workload-smoke` VM 없음
- `20260507-mixed5-workload-smoke` VPC 없음
- Terraform state에는 static Grafana IP, results bucket, archive data만 남음
- static Grafana IP와 GCS results bucket은 의도적으로 보존

## Portfolio Note

이번 작업은 자동 scaling을 구현했다는 의미가 아니다. mixed-room 환경에서 room workload를 판단하기 위한 관측 모델을 분리하고, 실제 GCP smoke로 `actual delivery work`, `conceptual work`, `scale decision work`가 같은 시간대에 남는 것을 확인한 단계다.

포트폴리오에서는 다음처럼 정리한다.

> 단일 hot room 수치 경쟁에서 벗어나, 여러 크기의 방이 섞인 workload에서 어떤 방이 실제로 위험한지 판단하기 위해 actual delivery work와 `input_msg_tps * active_sessions` 기반 conceptual work를 분리했다. GCP smoke로 DB/ack 정합성과 active/passive 동작을 유지한 채 workload observer metric이 수집되는 것을 검증했다.
