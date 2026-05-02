# GCP Load Test Automation

Terraform으로 VM 분리형 임시 GCP 부하테스트 환경을 만든다.

- LB VM: Nginx 실행
- app VM: Spring Boot backend만 실행
- MySQL VM: MySQL만 실행
- Redis VM: Redis만 실행
- k6 VM: 별도 VM에서 hot-room k6 테스트 실행
- 결과: 프로젝트 단위 고정 GCS bucket의 `runs/<run_id>/` prefix에 업로드
- 종료: k6 VM이 LB/app/MySQL/Redis VM과 자기 자신을 자동 삭제

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
  -var="app_count=1" \
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
  -var="app_count=2" \
  -var='vus_list=100 200 300' \
  -var="chat_duration_seconds=120"
```

결과 위치는 Terraform output의 `result_prefix`를 확인한다. 기본 bucket 이름은 `openchat-loadtest-<project-id>`로 고정되며, run별 결과는 `runs/<run_id>/` 아래에 쌓인다.

```bash
gcloud storage ls -r gs://<bucket>/runs/<run_id>/
```

## Sizing Profiles

현재 Terraform은 VM 기반 self-managed 구성을 유지한다. Cloud SQL, Memorystore 같은 관리형 DB/Redis는 사용하지 않는다.

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

```bash
gcloud compute project-info describe \
  --project=<gcp-project-id> \
  --format='table(quotas.metric,quotas.limit,quotas.usage)' \
  | grep CPUS_ALL_REGIONS
```

성능 개선 비교는 같은 profile과 같은 machine type에서만 수행한다. 서버 크기를 바꾸는 실험은 scale-up/scale-out 실험으로 별도 기록한다.

## Result Layout

```text
gs://<bucket>/runs/<run_id>/
  k6/<profile>/
    100vu-summary.json
    100vu.log
    100vu-exit-code.txt
    200vu-summary.json
    300vu-summary.json
  metrics/
    lb-health-after.json
    lb-prometheus-after.txt
    app-1-health-after.json
    app-1-prometheus-after.txt
  logs/
    lb-vm/
    app-vm/
    mysql-vm/
    redis-vm/
    k6-vm/
  run-metadata.json
```

k6 threshold가 실패해도 다음 VU 단계는 계속 실행된다. 각 단계의 k6 exit code는 `k6/<profile>/<vu>vu-exit-code.txt`에 저장된다.

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

GCS 결과 bucket은 `prevent_destroy=true`, `force_destroy=false`로 보호한다. run을 바꿔도 이전 `runs/<run_id>/` 결과를 삭제하지 않는다.

기존 run-specific bucket이 Terraform state에 남아 있는 상태에서 stable bucket 구조로 전환한다면, 원격 bucket을 삭제하지 않도록 bucket 관련 state만 분리한 뒤 apply한다.

```bash
terraform state rm google_storage_bucket.results
terraform state rm google_storage_bucket_object.source
terraform state rm google_storage_bucket_iam_member.runner_bucket_object_admin
```

## Notes

- Terraform은 현재 로컬 작업트리를 zip으로 묶어 GCS에 올린다. push하지 않은 브랜치 변경도 테스트 대상에 포함된다.
- `k6/results`, `.git`, `build`, `.gradle`, `.env`, dump/hprof 파일은 source archive에서 제외된다.
- SSH firewall rule은 기본으로 만들지 않는다. 필요하면 `ssh_source_ranges=["x.x.x.x/32"]`를 지정한다.
- LB VM의 `8080`은 k6 VM tag에서만 접근 가능하도록 제한한다.
- app VM의 `8080`은 LB/k6 VM tag에서만 접근 가능하도록 제한한다.
- MySQL/Redis는 app VM tag에서만 접근 가능하도록 제한한다.
- Cloud SQL/Memorystore는 사용하지 않는다. MySQL/Redis는 테스트용 VM 안에서 Docker 컨테이너로 실행된다.
