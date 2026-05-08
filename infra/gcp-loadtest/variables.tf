variable "project_id" {
  description = "GCP project id."
  type        = string
}

variable "region" {
  description = "GCP region for loadtest resources."
  type        = string
  default     = "asia-northeast3"
}

variable "zone" {
  description = "GCP zone for loadtest VMs."
  type        = string
  default     = "asia-northeast3-a"
}

variable "run_id" {
  description = "Unique run id used in resource names and GCS paths."
  type        = string
}

variable "bucket_name" {
  description = "Optional existing/new result bucket name. Empty value creates a project-stable bucket name."
  type        = string
  default     = ""
}

variable "profile" {
  description = "Logical loadtest profile name used in result paths and labels."
  type        = string
  default     = "vm-split"
}

variable "vus_list" {
  description = "Space-separated VU list for k6 fixed hot-room runs."
  type        = string
  default     = "100 200 300"
}

variable "chat_duration_seconds" {
  description = "WebSocket chat session duration for each VU."
  type        = number
  default     = 120
}

variable "send_interval_ms" {
  description = "Message send interval per VU."
  type        = number
  default     = 1000
}

variable "k6_ws_send_stop_after_seconds" {
  description = "Seconds after which WebSocket clients stop sending but keep connections open. Zero uses the full chat duration."
  type        = number
  default     = 0
}

variable "k6_chat_ack_p95_threshold_ms" {
  description = "k6 chat ack p95 threshold in milliseconds for mixed room workload scenarios."
  type        = number
  default     = 300
}

variable "k6_visible_freshness_p95_threshold_ms" {
  description = "k6 observer visible freshness p95 threshold in milliseconds. Set to 0 to collect the metric without failing the run."
  type        = number
  default     = 500
}

variable "k6_assignment_preflight_enabled" {
  description = "Wait for realtime node assignment readiness before k6 VUs start."
  type        = bool
  default     = false
}

variable "k6_assignment_preflight_expected_nodes" {
  description = "Minimum active realtime nodes expected by k6 assignment preflight. Zero disables the node count check."
  type        = number
  default     = 0

  validation {
    condition     = var.k6_assignment_preflight_expected_nodes >= 0
    error_message = "k6_assignment_preflight_expected_nodes must be zero or greater."
  }
}

variable "k6_assignment_preflight_timeout_seconds" {
  description = "Maximum seconds k6 setup waits for assignment readiness."
  type        = number
  default     = 60

  validation {
    condition     = var.k6_assignment_preflight_timeout_seconds >= 1
    error_message = "k6_assignment_preflight_timeout_seconds must be at least 1."
  }
}

variable "k6_node_drain_enabled" {
  description = "Trigger a realtime node drain during k6 validation."
  type        = bool
  default     = false
}

variable "k6_node_drain_after_seconds" {
  description = "Seconds after k6 starts before the coordinator triggers node drain."
  type        = number
  default     = 30
}

variable "k6_node_drain_limit" {
  description = "Maximum sessions to target per node drain reconnect command."
  type        = number
  default     = 1000
}

variable "k6_node_drain_retry_after_ms" {
  description = "Retry delay included in node drain reconnect controls."
  type        = number
  default     = 500
}

variable "connect_ramp_seconds" {
  description = "Seconds used by ramped k6 scenarios to spread login, room entry, and WebSocket connection attempts."
  type        = number
  default     = 60
}

variable "hot_rooms" {
  description = "Number of simultaneous hot rooms for multi-room k6 scenarios."
  type        = number
  default     = 1

  validation {
    condition     = var.hot_rooms >= 1
    error_message = "hot_rooms must be at least 1."
  }
}

variable "vus_per_room" {
  description = "Number of VUs assigned to each hot room for multi-room k6 scenarios."
  type        = number
  default     = 0

  validation {
    condition     = var.vus_per_room >= 0
    error_message = "vus_per_room must be zero or greater."
  }
}

variable "websocket_broadcast_lanes" {
  description = "Number of WebSocket broadcast lane workers per app VM."
  type        = number
  default     = 16

  validation {
    condition     = var.websocket_broadcast_lanes >= 1
    error_message = "websocket_broadcast_lanes must be at least 1."
  }
}

variable "live_publish_threads" {
  description = "Number of post-commit live publish worker threads per app VM."
  type        = number
  default     = 8

  validation {
    condition     = var.live_publish_threads >= 1
    error_message = "live_publish_threads must be at least 1."
  }
}

variable "scenario" {
  description = "k6 scenario path inside the source archive."
  type        = string
  default     = "k6/scenarios/07-hot-room-fixed.js"
}

variable "app_machine_type" {
  description = "Backend app VM machine type."
  type        = string
  default     = "e2-standard-4"
}

variable "app_count" {
  description = "Legacy backend app VM count. Used as realtime_count when realtime_count is 0."
  type        = number
  default     = 2

  validation {
    condition     = var.app_count >= 1
    error_message = "app_count must be at least 1."
  }
}

variable "api_count" {
  description = "Number of API-role backend VMs for non-WebSocket HTTP traffic."
  type        = number
  default     = 1

  validation {
    condition     = var.api_count >= 1
    error_message = "api_count must be at least 1."
  }
}

variable "realtime_count" {
  description = "Number of realtime-role backend VMs for WebSocket traffic. Set 0 to reuse app_count."
  type        = number
  default     = 0

  validation {
    condition     = var.realtime_count >= 0
    error_message = "realtime_count must be zero or greater."
  }
}

variable "api_machine_type" {
  description = "API-role backend VM machine type. Empty value reuses app_machine_type."
  type        = string
  default     = ""
}

variable "realtime_machine_type" {
  description = "Realtime-role backend VM machine type. Empty value reuses app_machine_type."
  type        = string
  default     = ""
}

variable "lb_machine_type" {
  description = "Nginx load balancer VM machine type."
  type        = string
  default     = "e2-small"
}

variable "mysql_machine_type" {
  description = "MySQL VM machine type."
  type        = string
  default     = "e2-standard-2"
}

variable "redis_machine_type" {
  description = "Redis VM machine type."
  type        = string
  default     = "e2-standard-2"
}

variable "k6_machine_type" {
  description = "k6 runner VM machine type."
  type        = string
  default     = "e2-standard-8"
}

variable "k6_worker_count" {
  description = "Number of k6 runner VMs. Values greater than 1 split hot rooms and VUs evenly unless shared_room_mode is true."
  type        = number
  default     = 1

  validation {
    condition     = var.k6_worker_count >= 1
    error_message = "k6_worker_count must be at least 1."
  }
}

variable "k6_cleanup_enabled" {
  description = "Whether the k6 coordinator deletes temporary VM resources after the run."
  type        = bool
  default     = true
}

variable "shared_room_mode" {
  description = "When true, distributed k6 workers join one coordinator-created room instead of splitting hot rooms."
  type        = bool
  default     = false
}

variable "k6_sender_ratio" {
  description = "Role-aware hot-room k6 sender client ratio."
  type        = number
  default     = 0.94
}

variable "k6_observer_ratio" {
  description = "Role-aware hot-room k6 observer client ratio."
  type        = number
  default     = 0.05
}

variable "k6_validator_ratio" {
  description = "Role-aware hot-room k6 validator client ratio."
  type        = number
  default     = 0.01
}

variable "observer_send_interval_ms" {
  description = "Send interval for observer clients. Zero disables observer sends."
  type        = number
  default     = 0
}

variable "active_user_ratio" {
  description = "Fraction of k6 VUs that declare room.active in active/passive hot-room scenarios."
  type        = number
  default     = 0.3

  validation {
    condition     = var.active_user_ratio >= 0 && var.active_user_ratio <= 1
    error_message = "active_user_ratio must be between 0 and 1."
  }
}

variable "active_heartbeat_interval_ms" {
  description = "Interval for k6 room.active.heartbeat control messages."
  type        = number
  default     = 20000

  validation {
    condition     = var.active_heartbeat_interval_ms > 0
    error_message = "active_heartbeat_interval_ms must be positive."
  }
}

variable "passive_settle_ms" {
  description = "Grace period after room.passive before k6 counts received full payloads as unexpected."
  type        = number
  default     = 2000

  validation {
    condition     = var.passive_settle_ms >= 0
    error_message = "passive_settle_ms must be zero or greater."
  }
}


variable "mixed_hot_room_count" {
  description = "Number of hot rooms in the mixed-room workload scenario."
  type        = number
  default     = 1
}

variable "mixed_hot_room_vus" {
  description = "VUs assigned to each hot room in the mixed-room workload scenario."
  type        = number
  default     = 40
}

variable "mixed_hot_active_ratio" {
  description = "Active user ratio for hot rooms in the mixed-room workload scenario."
  type        = number
  default     = 0.30
}

variable "mixed_hot_send_interval_ms" {
  description = "Sender interval for hot rooms in the mixed-room workload scenario."
  type        = number
  default     = 1000
}

variable "mixed_large_room_count" {
  description = "Number of large rooms in the mixed-room workload scenario."
  type        = number
  default     = 0
}

variable "mixed_large_room_vus" {
  description = "VUs assigned to each large room in the mixed-room workload scenario."
  type        = number
  default     = 120
}

variable "mixed_large_active_ratio" {
  description = "Active user ratio for large rooms in the mixed-room workload scenario."
  type        = number
  default     = 0.35
}

variable "mixed_large_send_interval_ms" {
  description = "Sender interval for large rooms in the mixed-room workload scenario."
  type        = number
  default     = 2000
}

variable "mixed_medium_room_count" {
  description = "Number of medium rooms in the mixed-room workload scenario."
  type        = number
  default     = 3
}

variable "mixed_medium_room_vus" {
  description = "VUs assigned to each medium room in the mixed-room workload scenario."
  type        = number
  default     = 15
}

variable "mixed_medium_active_ratio" {
  description = "Active user ratio for medium rooms in the mixed-room workload scenario."
  type        = number
  default     = 0.30
}

variable "mixed_medium_send_interval_ms" {
  description = "Sender interval for medium rooms in the mixed-room workload scenario."
  type        = number
  default     = 3000
}

variable "mixed_small_room_count" {
  description = "Number of small rooms in the mixed-room workload scenario."
  type        = number
  default     = 5
}

variable "mixed_small_room_vus" {
  description = "VUs assigned to each small room in the mixed-room workload scenario."
  type        = number
  default     = 3
}

variable "mixed_small_active_ratio" {
  description = "Active user ratio for small rooms in the mixed-room workload scenario."
  type        = number
  default     = 0.30
}

variable "mixed_small_send_interval_ms" {
  description = "Sender interval for small rooms in the mixed-room workload scenario."
  type        = number
  default     = 5000
}

variable "room_shard_enabled" {
  description = "Enable Redis room shard channels for app VMs."
  type        = bool
  default     = false
}

variable "room_shard_shard_count" {
  description = "Configured room shard count."
  type        = number
  default     = 1

  validation {
    condition     = var.room_shard_shard_count >= 1
    error_message = "room_shard_shard_count must be at least 1."
  }
}

variable "room_shard_legacy_subscribe_enabled" {
  description = "Whether app VMs also subscribe to legacy room channels while room shard mode is enabled."
  type        = bool
  default     = true
}

variable "room_partition_enabled" {
  description = "Enable hot-room fan-out partition channels."
  type        = bool
  default     = false
}

variable "room_partition_partition_count" {
  description = "Configured fan-out partition count."
  type        = number
  default     = 1

  validation {
    condition     = var.room_partition_partition_count >= 1
    error_message = "room_partition_partition_count must be at least 1."
  }
}

variable "room_partition_hot_tier_threshold" {
  description = "Minimum room scale tier that uses fan-out partitioning."
  type        = string
  default     = "CRITICAL"
}

variable "room_partition_max_partitions_per_room" {
  description = "Maximum fan-out partitions per room."
  type        = number
  default     = 16

  validation {
    condition     = var.room_partition_max_partitions_per_room >= 1
    error_message = "room_partition_max_partitions_per_room must be at least 1."
  }
}

variable "room_partition_admin_api_enabled" {
  description = "Enable internal room partition operation API for smoke/operation tooling."
  type        = bool
  default     = false
}

variable "room_partition_assignment_enabled" {
  description = "Enable realtime node registry and deterministic room partition assignment."
  type        = bool
  default     = false
}

variable "room_partition_assignment_dynamic_subscribe_enabled" {
  description = "Enable runtime Redis subscribe/unsubscribe based on partition assignment."
  type        = bool
  default     = false
}

variable "room_partition_assignment_node_drain_enabled" {
  description = "Enable node-drain commands for realtime partition assignment smoke tests."
  type        = bool
  default     = false
}

variable "room_partition_assignment_node_drain_readiness_timeout_ms" {
  description = "Maximum milliseconds node drain waits for replacement owners to become ready before reconnect."
  type        = number
  default     = 15000
}

variable "room_partition_lifecycle_enabled" {
  description = "Enable automatic room partition lifecycle scaling."
  type        = bool
  default     = false
}

variable "room_partition_lifecycle_interval_ms" {
  description = "Automatic room partition lifecycle scheduler interval."
  type        = number
  default     = 5000
}

variable "room_partition_lifecycle_scale_up_stable_window_ms" {
  description = "Stable hot-room observation window before automatic scale-up."
  type        = number
  default     = 30000
}

variable "room_partition_lifecycle_scale_up_min_observations" {
  description = "Minimum hot-room observations before automatic scale-up."
  type        = number
  default     = 2
}

variable "room_partition_lifecycle_scale_up_cooldown_ms" {
  description = "Per-room automatic scale-up cooldown."
  type        = number
  default     = 300000
}

variable "room_partition_lifecycle_scale_down_stable_window_ms" {
  description = "Stable low-work observation window before automatic scale-down."
  type        = number
  default     = 300000
}

variable "room_partition_lifecycle_scale_down_min_observations" {
  description = "Minimum low-work observations before automatic scale-down."
  type        = number
  default     = 3
}

variable "room_partition_lifecycle_scale_down_cooldown_ms" {
  description = "Per-room automatic scale-down cooldown."
  type        = number
  default     = 600000
}

variable "room_partition_lifecycle_scale_down_min_partition_age_ms" {
  description = "Minimum age since the last partition state update before automatic scale-down."
  type        = number
  default     = 600000
}

variable "room_partition_lifecycle_drain_complete_empty_observations" {
  description = "Consecutive empty drain observations required before completeDrain."
  type        = number
  default     = 2
}

variable "room_partition_lifecycle_drain_reconnect_retry_after_ms" {
  description = "Drain reconnect retry-after and throttle interval."
  type        = number
  default     = 500
}

variable "room_scale_pod_work_budget_delivery_per_sec" {
  description = "Room workload budget used by automatic lifecycle smoke recommendations."
  type        = number
  default     = 10000
}

variable "enable_monitoring" {
  description = "Whether to create a monitoring VM that runs Prometheus, Grafana, InfluxDB, and exporters."
  type        = bool
  default     = false
}

variable "monitoring_machine_type" {
  description = "Monitoring VM machine type."
  type        = string
  default     = "e2-standard-2"
}

variable "grafana_source_ranges" {
  description = "CIDR ranges allowed to access Grafana on port 3000. Empty keeps Grafana closed externally."
  type        = list(string)
  default     = []
}

variable "app_disk_size_gb" {
  description = "Backend app VM boot disk size."
  type        = number
  default     = 50
}

variable "lb_disk_size_gb" {
  description = "Load balancer VM boot disk size."
  type        = number
  default     = 20
}

variable "mysql_disk_size_gb" {
  description = "MySQL VM boot disk size. MySQL data lives on this disk and is deleted with the VM."
  type        = number
  default     = 50
}

variable "redis_disk_size_gb" {
  description = "Redis VM boot disk size."
  type        = number
  default     = 20
}

variable "k6_disk_size_gb" {
  description = "k6 VM boot disk size."
  type        = number
  default     = 30
}

variable "monitoring_disk_size_gb" {
  description = "Monitoring VM boot disk size."
  type        = number
  default     = 30
}

variable "vm_image" {
  description = "Compute Engine image for both VMs."
  type        = string
  default     = "projects/debian-cloud/global/images/family/debian-12"
}

variable "subnet_cidr" {
  description = "CIDR range for the temporary loadtest subnet."
  type        = string
  default     = "10.60.0.0/24"
}

variable "ssh_source_ranges" {
  description = "Optional CIDR ranges allowed to SSH into test VMs. Empty disables SSH firewall rule."
  type        = list(string)
  default     = []
}

variable "ttl_hours" {
  description = "Informational TTL label for cleanup."
  type        = number
  default     = 2
}

variable "realtime_workload_publish_enabled" {
  description = "Whether Realtime nodes publish workload snapshots to Redis."
  type        = bool
  default     = false
}

variable "realtime_workload_summary_enabled" {
  description = "Whether API nodes expose read-only realtime workload summary endpoints."
  type        = bool
  default     = false
}

variable "realtime_workload_publish_interval_ms" {
  description = "Realtime workload snapshot publish interval in milliseconds."
  type        = number
  default     = 5000

  validation {
    condition     = var.realtime_workload_publish_interval_ms >= 1000
    error_message = "realtime_workload_publish_interval_ms must be at least 1000."
  }
}

variable "realtime_workload_snapshot_ttl_ms" {
  description = "Realtime workload snapshot TTL in milliseconds."
  type        = number
  default     = 30000

  validation {
    condition     = var.realtime_workload_snapshot_ttl_ms >= 2000
    error_message = "realtime_workload_snapshot_ttl_ms must be at least 2000."
  }
}

variable "realtime_workload_top_room_limit" {
  description = "Maximum number of top room workload candidates included in each snapshot and summary."
  type        = number
  default     = 10

  validation {
    condition     = var.realtime_workload_top_room_limit >= 1
    error_message = "realtime_workload_top_room_limit must be at least 1."
  }
}
