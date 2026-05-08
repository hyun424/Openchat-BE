# Node Drain Orchestrator v1 Plan

## Summary

Drain Orchestrator v1 consumes the node drain status contract added in the previous branch. It does not terminate VMs, evict pods, or call MIG/EKS APIs. Its job is to drive the existing internal drain APIs until the application reports that the target node is safe to terminate.

The orchestrator is an external ops command, not an application scheduler. This keeps long polling and infrastructure decisions outside the app while still validating the app-level contract.

## Interface

Command:

```bash
scripts/openchat-node-drain-orchestrate.sh \
  --base-url http://127.0.0.1:8080 \
  --node-id gcp-realtime-1 \
  --token "$OPENCHAT_INTERNAL_TOKEN" \
  --timeout-seconds 180 \
  --poll-interval-ms 2000 \
  --reconnect-limit 1000 \
  --reconnect-retry-after-ms 500 \
  --max-reconnect-attempts 5 \
  --output /tmp/node-drain-result.json
```

Required inputs are `--base-url`, `--node-id`, and either `--token` or `OPENCHAT_INTERNAL_TOKEN`.

The default mode is `start`, which calls `POST drain` before polling. `status-only` mode observes an already-draining node without issuing the first `POST drain`.

## Decision Contract

The command uses `status`, `retryable`, `nextAction`, and `readinessReason`.

| nextAction | Behavior |
|---|---|
| `none` | Succeed only when `status=complete`. |
| `poll_status` | Sleep, then call `GET drain/status`. |
| `retry_reconnect` | Re-issue `POST drain` until max reconnect attempts. |
| `wait_assignment` | Sleep, then poll status. |
| `wait_replacement_ready` | Sleep, then poll status. |
| `wait_node_heartbeat` | Sleep, then poll status until timeout. |
| `add_replacement_node` | Exit blocked. |
| `fix_request` | Exit invalid usage. |
| `enable_node_drain` | Exit blocked. |
| `investigate_publish` | Retry reconnect within the same limit, then exit max retry. |

Exit codes:

- `0`: complete and `terminationAllowed=true`
- `1`: invalid usage or arguments
- `2`: blocked
- `3`: timeout
- `4`: max retry exceeded
- `5`: API/network/auth failure
- `6`: unexpected response shape

## GCP Validation

The existing direct curl node drain trigger remains available. A new `k6_node_drain_orchestrator_enabled` option switches the coordinator to this command and stores `metrics/node-drain-orchestrator-*.json`.

Acceptance:

- orchestrator exit code `0`
- `terminationAllowed=true`
- final status `complete`
- drained node openSessions `0`
- route failure/fallback/mismatch `0/0/0`
- sent == ack == DB rows
- node drain reconnect controls `> 0`
- cleanup leaves no RUN_ID GCE VM

## Boundaries

This version intentionally excludes VM stop/delete, MIG scale-in, EKS eviction, force close, durable reconnect command logs, and background orchestration.
