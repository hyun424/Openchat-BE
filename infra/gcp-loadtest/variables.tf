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
