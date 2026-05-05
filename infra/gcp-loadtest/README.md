# GCP Load Test Automation

Terraform으로 VM 분리형 임시 GCP 부하테스트 환경을 만든다.

- LB VM: Nginx 실행
- API VM: Spring Boot backend를 `APP_ROLE=api`로 실행하고 HTTP API 트래픽 처리
- Realtime VM: Spring Boot backend를 `APP_ROLE=realtime`로 실행하고 WebSocket 트래픽 처리
- MySQL VM: MySQL만 실행
- Redis VM: Redis만 실행
- k6 VM: 별도 VM에서 hot-room k6 테스트 실행. `k6_worker_count>1`이면 worker별로 hot room/VU를 나눠 실행
- Monitoring VM: `enable_monitoring=true`일 때 Prometheus, Grafana, InfluxDB, MySQL/Redis exporter 실행
- 결과: 프로젝트 단위 고정 GCS bucket의 `runs/<run_id>/` prefix에 업로드
- 종료: coordinator k6 VM이 LB/API/Realtime/MySQL/Redis/Monitoring/k6 worker VM과 자기 자신을 자동 삭제

## Prerequisites

로컬에 다음 도구가 필요하다.

```bash
terraform
gcloud
```

GCP project는 billing이 켜져 있어야 하고, Terraform을 실행하는 계정은 Compute Engine, IAM, Storage 리소스를 만들 권한이 있어야 한다.

필요 API:

```bash
gcloud services enable compute.googleapis.com storage.googleapis.com iam.googleapis.com
```

## Small Smoke Test

먼저 작은 부하로 자동 생성/업로드/삭제가 되는지 확인한다.

```bash
cd infra/gcp-loadtest
terraform init
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)" \
  -var="profile=vm-split" \
  -var="api_count=1" \
  -var="realtime_count=1" \
  -var="vus_list=10" \
  -var="chat_duration_seconds=30"
```

## Baseline Test

```bash
cd infra/gcp-loadtest
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)" \
  -var="profile=vm-split" \
  -var="api_count=1" \
  -var="realtime_count=2" \
  -var='vus_list=100 200 300' \
  -var="chat_duration_seconds=120"
```

결과 위치는 Terraform output의 `result_prefix`를 확인한다. 기본 bucket 이름은 `openchat-loadtest-<project-id>`로 고정되며, run별 결과는 `runs/<run_id>/` 아래에 쌓인다.

```bash
gcloud storage ls -r gs://<bucket>/runs/<run_id>/
```

## Sizing Profiles

현재 Terraform은 VM 기반 self-managed 구성을 유지한다. Cloud SQL, Memorystore 같은 관리형 DB/Redis는 사용하지 않는다. `app_count`는 호환용 변수이며 `realtime_count=0`일 때 realtime VM 수로만 사용된다. 새 실험은 `api_count`와 `realtime_count`를 명시한다.

현재 프로젝트의 `CPUS_ALL_REGIONS` quota가 12 vCPU라면 아래 profile만 바로 실행 가능하다.

```bash
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-quota12" \
  -var-file="profiles/quota-12-fixed.tfvars.example"
```

1000명 목표 self-managed profile은 최소 58 vCPU와 약 220GB의 regional SSD quota가 필요하다.

```bash
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-target1000" \
  -var-file="profiles/target-1000-self-managed.tfvars.example"
```

`terraform plan` 또는 `terraform apply` output의 `estimated_total_vcpus`, `estimated_total_ssd_gb`로 예상 vCPU와 SSD 사용량을 확인한다.

API/Realtime split 기준으로 한 방 1000명 fan-out/send 병목을 확인할 때는 아래 profile을 먼저 사용한다. 이 실험은 1000x3 aggregate 실험 전에 단일 hot room의 WebSocket lane/send 한계를 분리해서 확인하기 위한 것이다.

```bash
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-hr1000" \
  -var-file="profiles/hot-room-1000-role-split.tfvars.example" \
  -var="enable_monitoring=true" \
  -var='grafana_source_ranges=["<your-ip>/32"]'
```

```bash
gcloud compute project-info describe \
  --project=<gcp-project-id> \
  --format='table(quotas.metric,quotas.limit,quotas.usage)' \
  | grep CPUS_ALL_REGIONS
```

성능 개선 비교는 같은 profile과 같은 machine type에서만 수행한다. 서버 크기를 바꾸는 실험은 scale-up/scale-out 실험으로 별도 기록한다.

## Monitoring

`enable_monitoring=true`를 주면 monitoring VM이 Prometheus, Grafana, InfluxDB, MySQL exporter, Redis exporter를 Docker로 실행한다. Grafana external IP는 run id와 무관한 static address `openchat-loadtest-grafana-ip`를 사용하고, Terraform output의 `grafana_url`로 확인한다.

Grafana는 포트 `3000`만 외부에 열며, 반드시 접속할 IP를 CIDR로 제한한다. Prometheus `9090`과 InfluxDB `8086`은 외부에 공개하지 않는다. k6 worker는 내부 IP로 InfluxDB에 metric을 쓰고, Prometheus는 내부 IP로 LB/API/Realtime `/actuator/prometheus`를 scrape한다.

```bash
MY_IP="$(curl -fsS https://ifconfig.me)/32"

terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-monitoring-smoke" \
  -var="enable_monitoring=true" \
  -var="grafana_source_ranges=[\"$MY_IP\"]" \
  -var="api_count=1" \
  -var="realtime_count=1" \
  -var="vus_list=10" \
  -var="chat_duration_seconds=30"
```

Grafana 기본 계정은 `admin/admin`이고 anonymous viewer도 켜져 있다. Startup provisioning으로 다음 dashboard가 자동 로드된다.

- `OpenChat Server Pipeline`: ack, inbound, DB insert TPS, WebSocket delivery TPS/bytes, live publish, broadcast lane, outbox marker
- `OpenChat k6 E2E`: ack RTT, visible freshness, connect, received/omitted messages
- `OpenChat Infra`: JVM CPU/thread/GC, Hikari, MySQL/Redis exporter, Prometheus target

테스트 종료 후 monitoring VM은 k6 cleanup에서 삭제하지만 static IP는 Terraform `prevent_destroy=true`로 유지한다.

## Distributed k6

`k6_worker_count`를 2 이상으로 설정하면 k6 VM을 여러 대 만들고 GCS ready barrier 후 동시에 시작한다. `hot_rooms`와 `vus_list`의 각 값은 `k6_worker_count`로 나누어 떨어져야 한다. 예를 들어 3방 x 500명 control/distributed 비교는 아래처럼 실행한다.

Control run:

```bash
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-control" \
  -var-file="profiles/multi-hot-room-500x3.tfvars.example" \
  -var="enable_monitoring=true" \
  -var='grafana_source_ranges=["<your-ip>/32"]' \
  -var="k6_worker_count=1"
```

Distributed run:

```bash
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-distributed" \
  -var-file="profiles/multi-hot-room-500x3.tfvars.example" \
  -var="enable_monitoring=true" \
  -var='grafana_source_ranges=["<your-ip>/32"]' \
  -var="k6_worker_count=3"
```

판정은 control/distributed k6 p95와 서버 `ws.*since_client_sent`, `ws.broadcast.*since_created` metric을 같이 본다. distributed에서 k6 p95만 낮아지고 서버 lag도 낮으면 단일 k6 수신/측정 병목이고, 둘 다 10초대면 서버 fan-out/send 병목이다. k6 p95만 높고 서버 lag가 낮으면 클라이언트 관측 경로 병목이다.

`shared_room_mode=true`를 설정하면 여러 k6 worker가 방을 나누지 않고 coordinator가 만든 같은 roomId에 접속한다. 이 모드는 단일 hot-room 인원은 유지하고 k6 관측/수신 경로만 분산해서, 단일 k6 병목인지 서버 fan-out/send 병목인지 분리할 때 사용한다.

1800명 단일방 shared k6 run:

```bash
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-hr1800shared" \
  -var-file="profiles/hot-room-1800-shared-k6.tfvars.example" \
  -var="enable_monitoring=true" \
  -var='grafana_source_ranges=["<your-ip>/32"]'
```

이 실행은 전체 1800명을 유지하면서 `k6_worker_count=2`, worker당 900 VU로 나눠 같은 방에 접속한다. 결과는 worker별 summary와 함께 coordinator가 수집한 `k6/<profile>/shared/*-db-message-count.json`으로 전체 DB row count를 확인한다.

1500명 active/passive hot-room run:

```bash
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-hr1500ap" \
  -var-file="profiles/hot-room-1500-active-passive.tfvars.example"
```

이 실행은 전체 1500명이 같은 방에 접속하지만, 기본값 기준 약 450명만 `room.active`를 선언하고 약 1050명은 `room.passive`를 선언한다. 판정은 k6 `ws_presence_assigned_total`, `ws_passive_unexpected_messages_total`과 서버 `ws_session_max`, `ws_fanout_max`, `openchat_pipeline_events_total{event="ws.fanout.passive_omitted"}`를 함께 본다.

### 실측 TPS 확인

1000명 hot-room 재측정에서는 “100만 TPS”처럼 뭉뚱그려 표현하지 않고 아래 값을 분리해서 확인한다.

| 항목 | Prometheus query |
| --- | --- |
| 입력 TPS | `rate(openchat_pipeline_stage_seconds_count{stage="ws.inbound.total"}[1m])` |
| Ack TPS | `rate(openchat_pipeline_since_created_seconds_count{stage="ws.ack.after_send.since_client_sent"}[1m])` |
| DB 저장 TPS | `rate(openchat_pipeline_events_total{event="ingest.db_insert.success"}[1m])` |
| WebSocket logical delivery TPS | `rate(openchat_pipeline_events_total{event="ws.send.succeeded"}[1m])` |
| WebSocket frame TPS | `rate(openchat_pipeline_events_total{event="ws.send.frame.succeeded"}[1m])` |
| WebSocket send bytes/sec | `rate(openchat_pipeline_events_total{event="ws.send.bytes"}[1m])` |
| WebSocket send p95 | `openchat_pipeline_stage_seconds{stage="ws.send.duration",quantile="0.95"}` |

k6 worker는 `client_message_id` prefix를 test label로 넣고, 실행 후 MySQL에서 `chat_message.client_message_id LIKE '<label>-%'` row count를 수집한다. 결과는 worker 결과 디렉터리의 `*-db-message-count.json`에 저장된다.

## Result Layout

```text
gs://<bucket>/runs/<run_id>/
  k6/<profile>/
    single/
      100vu-summary.json
      100vu.log
      100vu-exit-code.txt
    worker-1/
      500vu-summary.json
      500vu.log
    worker-2/
      500vu-summary.json
    worker-3/
      500vu-summary.json
  metrics/
    lb-health-before.json
    lb-prometheus-before.txt
    lb-health-after.json
    lb-prometheus-after.txt
    app-1-health-before.json
    app-1-prometheus-before.txt
    app-1-health-after.json
    app-1-prometheus-after.txt
  logs/
    lb-vm/
    app-vm/
    mysql-vm/
    redis-vm/
    k6-vm/
      single/
      worker-1/
  workers/
    worker-1.ready
    worker-1.done
  run-metadata-<worker>.json
```

k6 threshold가 실패해도 다음 VU 단계는 계속 실행된다. 각 단계의 k6 exit code는 `k6/<profile>/<worker>/<vu>vu-exit-code.txt`에 저장된다.

## Cleanup

k6 VM startup script가 테스트 종료 시 VM 삭제를 시도한다. 실패했거나 중간에 끊긴 경우 다음 label로 수동 정리한다.

```bash
gcloud compute instances list \
  --filter='labels.app=openchat AND labels.purpose=loadtest'
```

필요하면 Terraform으로 남은 리소스를 정리한다.

```bash
terraform destroy -var="project_id=<gcp-project-id>" -var="run_id=<same-run-id>"
```

GCS 결과 bucket과 Grafana static IP는 `prevent_destroy=true`로 보호한다. run을 바꿔도 이전 `runs/<run_id>/` 결과와 Grafana IP를 삭제하지 않는다.

기존 run-specific bucket이 Terraform state에 남아 있는 상태에서 stable bucket 구조로 전환한다면, 원격 bucket을 삭제하지 않도록 bucket 관련 state만 분리한 뒤 apply한다.

```bash
terraform state rm google_storage_bucket.results
terraform state rm google_storage_bucket_object.source
terraform state rm google_storage_bucket_iam_member.runner_bucket_object_admin
```

## Notes

- Terraform은 현재 로컬 작업트리를 zip으로 묶어 GCS에 올린다. push하지 않은 브랜치 변경도 테스트 대상에 포함된다.
- `k6/results`, `.git`, `build`, `.gradle`, `.env`, dump/hprof 파일은 source archive에서 제외된다.
- SSH firewall rule은 기본으로 만들지 않는다. 일반 VM은 external IP 없이 Cloud NAT로 outbound만 사용하므로 SSH가 필요하면 별도 접속 방식을 함께 준비해야 한다.
- LB VM의 `8080`은 k6/monitoring VM tag에서만 접근 가능하도록 제한한다.
- app VM의 `8080`은 LB/k6/monitoring VM tag에서만 접근 가능하도록 제한한다.
- MySQL은 app/k6/monitoring VM tag에서만 접근 가능하도록 제한한다. k6는 run label 기준 DB row count 검증에만 MySQL 접근을 사용한다.
- Redis는 app/monitoring VM tag에서만 접근 가능하도록 제한한다.
- Grafana `3000`은 `grafana_source_ranges`에서만 접근 가능하다.
- Cloud SQL/Memorystore는 사용하지 않는다. MySQL/Redis는 테스트용 VM 안에서 Docker 컨테이너로 실행된다.
