#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN_TF="$ROOT_DIR/infra/gcp-loadtest/main.tf"

grep -q 'run_hash *= *substr(sha1(var.run_id), 0, 8)' "$MAIN_TF"
grep -q 'name_prefix *=.*local.run_hash' "$MAIN_TF"
grep -q 'service_account_id *=.*local.run_hash' "$MAIN_TF"
grep -q 'account_id *= *local.service_account_id' "$MAIN_TF"

if grep -q 'account_id *= *replace(substr(local.name_prefix, 0, 30)' "$MAIN_TF"; then
  echo "service account id must not be derived by truncating name_prefix without hash suffix" >&2
  exit 1
fi

service_account_id_for() {
  local run_id="$1"
  local safe_run_id
  local run_hash
  safe_run_id="$(printf '%s' "$run_id" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9-]/-/g')"
  run_hash="$(printf '%s' "$run_id" | shasum -a 1 | awk '{print substr($1, 1, 8)}')"
  printf 'oclt-%s-%s' "${safe_run_id:0:16}" "$run_hash" | sed -E 's/-+$//'
}

first_id="$(service_account_id_for "20260509-rolling-restart-mini-soak2")"
second_id="$(service_account_id_for "20260509-rolling-restart-visibility-load")"

if [ "$first_id" = "$second_id" ]; then
  echo "long rolling restart run ids must not collide after service account shortening" >&2
  exit 1
fi

if [ "${#first_id}" -gt 30 ] || [ "${#second_id}" -gt 30 ]; then
  echo "service account ids must stay within GCP 30 character limit" >&2
  exit 1
fi

echo "gcp loadtest resource naming tests passed"
