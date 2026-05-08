# Node Termination Contract v1 Plan

## Summary

Node drain orchestrator already proves that an OpenChat realtime node can reach `complete` with `terminationAllowed=true`. This step adds a provider-neutral decision gate that reads the orchestrator result and decides whether an external system may terminate the node.

This is intentionally not a provider framework. It does not call GCP, MIG, EKS, Terraform, `gcloud`, or `kubectl`. It only validates the app-level drain result and emits a stable decision JSON plus exit code for future adapters.

## Contract

`scripts/openchat-node-termination-decision.sh` accepts:

- `--input ORCHESTRATOR_RESULT_JSON`
- `--node-id NODE_ID`
- `--output PATH` optional

The node is ready for termination only when all guards pass:

- input JSON is valid
- required fields are present
- `nodeId` matches the requested node
- `terminationAllowed=true`
- `result=complete`
- `lastStatus=complete`
- `lastNextAction=none`
- `remainingSessions=0`

The output JSON includes:

- `result`
- `terminationAllowed`
- `recommendedAction`
- `nodeId`
- source status fields
- `remainingSessions`
- `reason`
- guard results
- `createdAt`

## Result Meaning

- `ready`: safe for a provider adapter to terminate the node.
- `not_ready`: drain has not completed or sessions remain.
- `unsafe`: the input does not match the requested node or violates a safety guard.
- `invalid_input`: caller passed invalid arguments.
- `unexpected_input`: JSON is invalid or missing required fields.

## GCP Harness

The GCP node drain smoke can enable this decision gate after orchestrator completion. The harness stores `metrics/node-termination-decision-*.json` and fails the run if the decision does not exit `0`.

Actual VM stop/delete remains out of scope for this step.

## Validation

- Fixture shell tests cover ready, not ready, unsafe, invalid JSON, missing fields, missing args, and output file behavior.
- Existing node drain orchestrator tests continue to pass.
- GCP smoke confirms orchestrator completion plus termination decision `ready`.
