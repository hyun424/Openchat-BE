# Room Partition Auto Lifecycle Scaling

## Goal

Automate the full partition lifecycle for hot rooms:

1. detect a stable hot room from cluster workload summary,
2. scale the room up,
3. reconnect existing clients so routes redistribute,
4. detect sustained low workload,
5. drain high partition ids safely,
6. complete scale-down only after cluster-wide draining sessions reach zero.

Production defaults stay disabled. GCP smoke profiles explicitly enable the lifecycle loop.

## Runtime Controls

`app.room-partition.lifecycle.enabled=false` is the production default. When enabled, one scheduler loop reads `RealtimeWorkloadClusterSummary`, acquires a Redis lease, and runs at most `max-actions-per-run` lifecycle actions.

Lease key:

```text
openchat:room-partition:lifecycle:lease
```

Room action cooldown keys:

```text
openchat:room-partition:lifecycle:cooldown:{operation}:{roomId}
```

The lease uses Redis `SET NX PX` semantics through `StringRedisTemplate.setIfAbsent`. It is intentionally not released; TTL prevents duplicate scheduler work across nodes.

## Scale-Up

Scale-up uses `SCALE_UP_CANDIDATE` recommendations and the matching `topRooms` entry. If multiple rooms qualify, the service handles the room with the highest `scaleDecisionWorkPerSecond`.

Safety gates:

- stale node recommendation exists: skip
- send failure recommendation exists: skip
- state row missing: skip
- state is not `ACTIVE`: skip
- stable observation count/window not satisfied: skip
- target partition count is not greater than current count: skip
- room cooldown is active: skip

Target:

```text
candidateTarget = max(candidate.effectivePartitions, currentPartitions + 1, 2)
target = min(candidateTarget, maxPartitionsPerRoom, configuredPartitionCount)
```

Execution:

1. `scaleUp(roomId, target, updatedBy)` changes `partitionCount`, increments route version, and sets `SCALING_UP`.
2. `RoomPartitionRedistributionService` sends reconnect commands to old partitions.
3. `completeScaleUp(roomId, updatedBy)` returns the state to `ACTIVE` without changing `partitionCount` or incrementing route version.

Reconnect publish failures do not roll back scale-up. They are logged and counted, and the next scheduler tick may re-evaluate workload.

## Scale-Down And Drain

Scale-down is conservative and defaults to auto-managed rooms only. With `auto-managed-only=true`, candidates must have `updatedBy` beginning with the lifecycle `updated-by` prefix.

Candidate state requirements:

- `partitionCount > 1`
- `status = ACTIVE`
- optional auto-managed `updatedBy` prefix
- partition state age exceeds `min-partition-age-ms`

Workload source:

- If the room appears in `summary.topRooms`, use `scaleDecisionWorkPerSecond`.
- If absent, treat observed work as `0`.
- If any stale node exists, skip all scale-down decisions.

The room must remain below the watch threshold for the configured observation count and stable window.

Target:

```text
target = max(1, currentPartitions / 2)
```

Drain partitions are the highest ids:

- `4 -> 2`: drain `2,3`
- `2 -> 1`: drain `1`

Drain completion requires cluster summary drain progress to report zero open sessions on every draining partition for `complete-empty-observations` consecutive observations. Until then, the lifecycle loop periodically sends reconnect controls through `reconnectDraining(roomId, "scale_down", retryAfter, limit)` with a separate cooldown.

## Snapshot Extension

Realtime node snapshots include local drain progress:

```text
roomId, partitionId, openSessions, sourceNodeId
```

Cluster summary aggregates by `(roomId, partitionId)` and exposes the totals. Old snapshot JSON without the new field is read as an empty list.

## Metrics And Logs

Metric:

```text
openchat_room_partition_lifecycle_event_total
```

Tags:

- `operation`: `scale_up`, `redistribute`, `scale_down`, `drain_reconnect`, `drain_complete`
- `result`: low-cardinality result such as `success`, `skip_not_stable`, `skip_cooldown`, `publish_failed`

Room ids, partition ids, session ids, and user ids are excluded from Prometheus tags. Detailed room-level context is logged.

## GCP Smoke

Run id:

```text
20260508-auto-partition-lifecycle-smoke
```

Smoke profile should enable:

- room partitioning
- realtime workload summary
- lifecycle scheduler
- low workload budget to force `SCALE_UP_CANDIDATE`
- short stable windows and cooldowns
- short scale-down minimum age

Expected phases:

1. hot send phase causes partition count to increase,
2. reconnect control redistributes existing clients,
3. low-workload phase starts drain,
4. settle phase reaches zero draining sessions and completes drain.

Acceptance:

- k6 exit code `0`
- HTTP error `0%`
- WebSocket connect success `100%`
- `/ws-route` success `100/100`
- sent == ack == DB rows
- no `getWsRoute failed`, duplicate key, or partition exception in API logs
- hot room partition count increases
- reconnect control sent count `> 0`
- draining sessions reach `0`
- final `room_partition_state.status=ACTIVE`
- final partition count is below the scale-up peak
- cleanup leaves no GCE VM residue
