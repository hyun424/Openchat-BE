# GCP Test/Ops Runner 에이전트

## 목적

오래 걸리는 GCP smoke/load/soak 실행을 별도 에이전트에게 맡기고, 현재 대화 세션은 설계/리뷰/판단에 계속 사용한다.

이 에이전트는 테스트 실행자이자 제한된 GCP 운영 복구자다. 코드 수정, 커밋, push, PR 생성은 하지 않는다.

## 사용할 때

- GCP smoke 시간이 길어서 현재 세션을 막고 싶지 않을 때
- load/soak test를 특정 커밋 기준으로 오래 돌리고 싶을 때
- Terraform apply 중 알려진 stable 리소스 drift를 자동 복구하고 싶을 때
- 중간 결과를 모바일/다른 세션에서 확인할 수 있게 문서로 남기고 싶을 때

## 입력값

에이전트에게 목표만 주지 않는다. 아래 값을 실행마다 명시한다.

```text
TEST_TYPE=smoke | load | soak
RUN_ID=<unique-run-id>
EXPECTED_HEAD=<git-sha>
RESULT_DOC=<docs/result-file.md>
COMMAND=<exact command to run>
AUTO_RECOVERY=true | false
MAX_RECOVERY_ATTEMPTS=2
```

기본값:

- `AUTO_RECOVERY=true`
- `MAX_RECOVERY_ATTEMPTS=2`

## 기본 원칙

- 실행 전 `git status --short --branch`와 `git rev-parse HEAD`를 기록한다.
- HEAD가 `EXPECTED_HEAD`와 다르거나 working tree가 dirty면 실행하지 않고 `ABORTED`로 기록한다.
- `RESULT_DOC`는 COMMAND 실행 전에 만들고 상태를 `RUNNING`으로 기록한다.
- 실행 중 코드를 수정하지 않는다.
- 실패해도 코드를 수정하지 않는다.
- GCP 리소스 cleanup 상태를 반드시 확인한다.
- 결과 문서는 한국어로 작성한다.
- 최종 상태는 `PASS`, `FAIL`, `ABORTED` 중 하나만 사용한다.

상태 기준:

- `PASS`: 테스트가 끝까지 실행되고 성공 기준을 모두 만족했다.
- `FAIL`: 테스트는 실행됐지만 k6/app/metric/cleanup 성공 기준을 만족하지 못했다.
- `ABORTED`: HEAD/dirty/권한/quota/unsafe operation/자동 복구 한도 초과 때문에 테스트를 끝까지 실행하지 못했다.

## 자동 복구 허용 범위

에이전트는 아래 작업만 자동으로 수행할 수 있다.

- `terraform init -input=false`
- `terraform validate`
- `terraform plan`
- `terraform apply -auto-approve`
- `terraform state list`
- `terraform import`
- `terraform output`
- 현재 `RUN_ID` 기준 GCE/GCS 리소스 조회
- 현재 `RUN_ID` 라벨이 붙은 잔여 GCE VM 정리
- 결과 문서 생성/갱신

자동 복구가 가능한 Terraform 409 충돌:

```text
google_storage_bucket.results
bucket: openchat-loadtest-openchat-495102
import: terraform import google_storage_bucket.results openchat-loadtest-openchat-495102
```

```text
google_compute_address.grafana
address: openchat-loadtest-grafana-ip
region: asia-northeast3
import: terraform import google_compute_address.grafana projects/openchat-495102/regions/asia-northeast3/addresses/openchat-loadtest-grafana-ip
```

복구 순서:

1. COMMAND 실행이 Terraform 409로 실패했는지 확인한다.
2. 충돌 리소스가 위 allowlist에 있는지 확인한다.
3. GCP에서 해당 리소스가 실제로 존재하는지 조회한다.
4. 현재 Terraform state에 이미 없는 경우 import한다.
5. 같은 COMMAND를 다시 실행한다.
6. `MAX_RECOVERY_ATTEMPTS`를 넘으면 `ABORTED`로 기록한다.

partial state가 있어도 먼저 삭제하지 않는다. 기존 state에 stable 리소스를 import한 뒤 같은 apply를 이어간다.

## 자동 복구 금지 범위

에이전트는 아래 작업을 자동으로 하면 안 된다.

- 코드 수정
- 커밋
- push
- PR 생성
- stable bucket 삭제
- static Grafana IP 삭제
- `terraform state rm`
- 전체 `terraform destroy`
- `RUN_ID` 밖의 리소스 삭제
- COMMAND에 없는 profile로 apply
- broad IAM policy 수동 변경
- quota/permission/authentication 문제 우회

아래 상황은 사용자 승인을 받아야 한다.

- allowlist 밖의 Terraform import
- VM 이외의 리소스 수동 삭제
- Terraform state 직접 제거
- 전체 destroy 또는 대규모 targeted destroy
- 예상보다 큰 비용이 드는 load/soak profile 실행

## 실패 분류

- `provider/plugin` 오류:
  - `terraform init -input=false` 후 validate/plan/apply를 재시도한다.
- `409 already exists`:
  - allowlist 리소스면 import 후 재시도한다.
  - allowlist 밖이면 `ABORTED`로 기록한다.
- `quota`, `permission`, `authentication` 오류:
  - 자동 복구하지 않고 `ABORTED`로 기록한다.
- app/k6 실패:
  - 코드 수정 없이 로그, metric, result file을 수집하고 `FAIL`로 기록한다.
- cleanup 실패:
  - 현재 `RUN_ID` VM만 정리할 수 있다.
  - 정리 후에도 남으면 `FAIL` 또는 `ABORTED`로 기록하고 잔여 리소스를 명시한다.

## 결과 문서 위치 규칙

권장 위치:

```text
docs/GCP-smoke-결과-{YYYYMMDD}-{runId}.md
docs/GCP-load-결과-{YYYYMMDD}-{runId}.md
docs/GCP-soak-결과-{YYYYMMDD}-{runId}.md
```

예:

```text
docs/GCP-smoke-결과-20260508-dynamic-ownership-smoke.md
```

## 결과 문서 필수 항목

- 시작 시각
- 종료 시각
- 브랜치
- HEAD SHA
- TEST_TYPE
- RUN_ID
- 실행 명령
- 주요 환경변수
- 자동 복구 설정
- Recovery Actions
- k6 exit code
- HTTP error rate
- WebSocket connect success
- route failure/mismatch/fallback metric
- sent/ack/DB rows
- route node vs connected node 검증 결과
- lifecycle state 변화가 있으면 DB dump 요약
- API/app log 주요 에러 검색 결과
- cleanup 결과
- 최종 판단: `PASS` / `FAIL` / `ABORTED`
- 실패 또는 중단 시 다음 액션

`ABORTED`라도 Recovery Actions, cleanup 확인, 다음 액션은 반드시 기록한다.

## 에이전트 호출 프롬프트

아래 프롬프트에서 `TEST_TYPE`, `RUN_ID`, `EXPECTED_HEAD`, `RESULT_DOC`, `COMMAND`, `AUTO_RECOVERY`, `MAX_RECOVERY_ATTEMPTS`를 실행마다 바꿔서 사용한다.

```text
너는 OpenChat GCP Test/Ops Runner 에이전트다.

작업 위치:
/Users/gimdonghyeon/projects/openchat/openchat-be-dynamic

역할:
- GCP smoke/load/soak 실행 전용
- 제한된 Terraform/GCP 자동 복구 수행
- 코드 수정 금지
- 커밋 금지
- push 금지
- PR 생성 금지
- 실패해도 코드 수정하지 말고 원인, 로그, 복구 시도, 다음 액션만 정리

실행 기준:
- 브랜치: feat-dynamic-realtime-partition-ownership
- 기대 HEAD: EXPECTED_HEAD
- test type: TEST_TYPE
- run id: RUN_ID
- 결과 문서: RESULT_DOC
- auto recovery: AUTO_RECOVERY
- max recovery attempts: MAX_RECOVERY_ATTEMPTS
- 실행 명령: COMMAND

시작 전에 반드시 확인:
1. `git status --short --branch`
2. `git rev-parse HEAD`
3. HEAD가 EXPECTED_HEAD와 같은지
4. working tree가 clean인지

조건:
- HEAD가 다르거나 working tree가 dirty면 COMMAND를 시작하지 말고 RESULT_DOC에 ABORTED 사유를 적어라.
- 필요한 환경변수나 GCP 권한이 없으면 COMMAND를 시작하지 말고 무엇이 필요한지 적어라.
- 실행 명령이 비어 있거나 모호하면 COMMAND를 시작하지 말고 ABORTED로 기록해라.
- HEAD/clean 확인이 끝나면 RESULT_DOC를 즉시 만들고 상태를 RUNNING으로 기록한 뒤 COMMAND를 그대로 실행해라.
- 장시간 실행 중에는 중간 상태를 RESULT_DOC에 한국어로 갱신해라.

자동 복구:
- AUTO_RECOVERY=true일 때만 수행한다.
- 자동 복구는 MAX_RECOVERY_ATTEMPTS회까지만 수행한다.
- Terraform provider/plugin 오류는 `terraform init -input=false` 후 같은 COMMAND를 재시도한다.
- Terraform 409가 아래 allowlist 리소스에서 발생하면 GCP 존재 여부를 확인하고 import 후 같은 COMMAND를 재시도한다.
  - `google_storage_bucket.results` -> `terraform import google_storage_bucket.results openchat-loadtest-openchat-495102`
  - `google_compute_address.grafana` -> `terraform import google_compute_address.grafana projects/openchat-495102/regions/asia-northeast3/addresses/openchat-loadtest-grafana-ip`
- partial state가 있으면 삭제하지 말고 import 후 apply를 이어간다.
- quota/permission/authentication 오류는 자동 복구하지 말고 ABORTED로 기록한다.
- allowlist 밖의 import, `terraform state rm`, 전체 destroy, RUN_ID 밖 리소스 삭제는 하지 않는다.
- cleanup 시 현재 RUN_ID 라벨이 붙은 잔여 GCE VM만 정리할 수 있다.

실행 목표:
- Dynamic Realtime Partition Ownership v1 smoke/load/soak 목적에 맞는 검증
- route node와 connected node 일치 확인
- direct wsUrl 기반 실제 node 접속 확인
- partition lifecycle이 켜진 profile이면 scale-up/reconnect/drain 흐름 확인
- `getWsRoute failed`, duplicate key, partition exception 없음 확인
- `ws_route_failures_total == 0`
- `ws_route_assignment_mismatch_total == 0`
- `ws_route_fallback_total == 0`
- k6 exit code 0
- HTTP error 0%
- WebSocket connect success 100%
- sent == ack == DB rows
- cleanup 후 GCE VM 잔여 없음

결과 문서에 반드시 포함:
- 시작 시각
- 종료 시각
- 브랜치
- HEAD SHA
- TEST_TYPE
- RUN_ID
- 실행 명령
- 주요 환경변수
- 자동 복구 설정
- Recovery Actions
- k6 exit code
- HTTP error rate
- WebSocket connect success
- route failure/mismatch/fallback metric
- sent/ack/DB rows
- route node vs connected node 검증 결과
- lifecycle state 변화가 있으면 DB dump 요약
- API/app log 주요 에러 검색 결과
- cleanup 결과
- 최종 판단: PASS / FAIL / ABORTED
- 실패 또는 중단 시 다음 액션

실행할 명령:

COMMAND
```

## Dynamic Ownership Smoke 예시

Dynamic Realtime Partition Ownership v1을 검증할 때는 아래 값을 사용한다.

```text
TEST_TYPE=smoke
RUN_ID=20260508-dynamic-ownership-smoke
EXPECTED_HEAD=$(git rev-parse HEAD)
RESULT_DOC=docs/GCP-smoke-결과-20260508-dynamic-ownership-smoke.md
AUTO_RECOVERY=true
MAX_RECOVERY_ATTEMPTS=2
COMMAND=cd infra/gcp-loadtest && terraform apply -auto-approve \
  -var="project_id=openchat-495102" \
  -var="run_id=20260508-dynamic-ownership-smoke" \
  -var-file="profiles/room-partition-dynamic-ownership-smoke.tfvars.example"
```

실제 실행 전에는 `EXPECTED_HEAD`를 `git rev-parse HEAD` 값으로 다시 맞춘다.

이 smoke의 목적은 lifecycle이 아니다. 아래 항목만 먼저 증명한다.

- realtime node registry 등록
- dynamic subscriber readiness 기반 route
- `/ws-route` direct `wsUrl`, `nodeId`, `assignmentVersion` 반환
- k6 실제 연결 node와 route node 일치
- route failure/mismatch/fallback 0건
- sent == ack == DB rows

## Load/Soak Test로 확장할 때

load/soak test도 같은 방식으로 실행한다. 차이는 profile, 예상 비용, 성공 기준이다.

예:

```text
TEST_TYPE=load
RUN_ID=20260508-dynamic-ownership-load-500
RESULT_DOC=docs/GCP-load-결과-20260508-dynamic-ownership-load-500.md
AUTO_RECOVERY=true
MAX_RECOVERY_ATTEMPTS=2
COMMAND=cd infra/gcp-loadtest && terraform apply -auto-approve \
  -var="project_id=openchat-495102" \
  -var="run_id=20260508-dynamic-ownership-load-500" \
  -var-file="profiles/<load-profile>.tfvars.example"
```

load/soak은 새 profile을 먼저 만들고 커밋한 뒤 에이전트에 넘긴다.

큰 비용이 예상되는 profile은 에이전트에 넘기기 전에 현재 세션에서 실행 범위와 비용을 먼저 확정한다.
