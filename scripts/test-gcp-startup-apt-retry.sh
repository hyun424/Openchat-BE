#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEMPLATE_DIR="$ROOT_DIR/infra/gcp-loadtest/templates"

templates=(
  "app-startup.sh.tftpl"
  "k6-startup.sh.tftpl"
  "lb-startup.sh.tftpl"
  "monitoring-startup.sh.tftpl"
  "mysql-startup.sh.tftpl"
  "redis-startup.sh.tftpl"
)

for template in "${templates[@]}"; do
  path="$TEMPLATE_DIR/$template"
  grep -q '^apt_update_with_retry()' "$path"
  grep -q '^apt_install_with_retry()' "$path"
done

if grep -RInE '^[[:space:]]*apt-get update($|[[:space:]])' "$TEMPLATE_DIR" \
  | grep -v 'if apt-get update -o Acquire::Retries=3; then'; then
  echo "raw apt-get update found outside retry helper" >&2
  exit 1
fi

if grep -RInE '^[[:space:]]*apt-get install -y($|[[:space:]])' "$TEMPLATE_DIR" \
  | grep -v 'if apt-get install -y "$@"; then'; then
  echo "raw apt-get install found outside retry helper" >&2
  exit 1
fi

echo "GCP startup apt retry checks passed."
