# GCP VM Termination Adapter v1 Plan

## Summary

This step connects the provider-neutral node termination decision to GCP VM stop. The goal is not to implement MIG scale-in or VM delete. The goal is to prove that a drained realtime node can be stopped and the remaining realtime node continues to handle routing and fanout.

The adapter only targets run-scoped GCP loadtest realtime VMs. It refuses shared resources, mismatched run ids, non-realtime roles, node id mismatches, and stale or non-ready termination decisions.

## Contract

`scripts/openchat-gcp-node-terminate.sh` accepts:

- `--decision PATH`
- `--project PROJECT_ID`
- `--zone ZONE`
- `--run-id RUN_ID`
- `--node-id NODE_ID`
- `--mode dry-run|stop`
- `--instance INSTANCE_NAME` optional
- `--output PATH` optional

When `--instance` is omitted, the adapter resolves the instance from GCE labels:

- `labels.run_id=<run_id>`
- `labels.role=realtime`
- `labels.app_index=<N>` where `nodeId=gcp-realtime-N`

## Safety Guards

The adapter proceeds only when:

- decision result is `ready`
- decision `terminationAllowed=true`
- decision `recommendedAction=terminate_node`
- decision `nodeId` matches the requested node
- GCE instance exists in the requested project and zone
- GCE labels match the run id, role `realtime`, and expected app index
- mode is `dry-run` or `stop`
- stop mode targets a currently `RUNNING` instance

## Output

The adapter emits JSON with:

- `result`
- `terminationPerformed`
- `mode`
- `nodeId`
- `instance`
- `project`
- `zone`
- `runId`
- `beforeStatus`
- `afterStatus`
- `guards`
- `reason`
- `createdAt`
- `completedAt`

## GCP Smoke

The node drain smoke profile enables the adapter in `stop` mode after the node drain orchestrator and termination decision both succeed. The expected GCP evidence is:

- adapter result `stopped`
- target realtime VM status `TERMINATED`
- drained node openSessions `0`
- route failure/fallback/mismatch `0/0/0`
- sent == ack == DB rows
- cleanup leaves no RUN_ID VM behind
