terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

locals {
  safe_run_id        = lower(replace(var.run_id, "/[^a-z0-9-]/", "-"))
  name_prefix        = substr("openchat-lt-${local.safe_run_id}", 0, 40)
  sanitized_project  = lower(replace(var.project_id, "/[^a-z0-9-]/", "-"))
  default_bucket     = substr(lower(replace("openchat-loadtest-${local.sanitized_project}", "/[^a-z0-9._-]/", "-")), 0, 63)
  bucket_name        = var.bucket_name != "" ? var.bucket_name : local.default_bucket
  source_object_name = "runs/${var.run_id}/source/openchat-source.zip"

  lb_vm_name         = "${local.name_prefix}-lb"
  mysql_vm_name      = "${local.name_prefix}-mysql"
  redis_vm_name      = "${local.name_prefix}-redis"
  k6_vm_names        = var.k6_worker_count == 1 ? ["${local.name_prefix}-k6"] : [for index in range(var.k6_worker_count) : "${local.name_prefix}-k6-${index + 1}"]
  monitoring_vm_name = "${local.name_prefix}-monitoring"

  effective_realtime_count        = var.realtime_count > 0 ? var.realtime_count : var.app_count
  effective_api_machine_type      = var.api_machine_type != "" ? var.api_machine_type : var.app_machine_type
  effective_realtime_machine_type = var.realtime_machine_type != "" ? var.realtime_machine_type : var.app_machine_type

  common_labels = {
    app       = "openchat"
    purpose   = "loadtest"
    run_id    = lower(replace(var.run_id, "/[^a-z0-9_-]/", "_"))
    ttl_hours = tostring(var.ttl_hours)
  }

  bucket_labels = {
    app     = "openchat"
    purpose = "loadtest"
  }

  machine_type_vcpus = {
    e2-micro       = 2
    e2-small       = 2
    e2-medium      = 2
    e2-standard-2  = 2
    e2-standard-4  = 4
    e2-standard-8  = 8
    e2-standard-16 = 16
    e2-standard-32 = 32
    e2-highmem-2   = 2
    e2-highmem-4   = 4
    e2-highmem-8   = 8
    e2-highmem-16  = 16
    e2-highcpu-2   = 2
    e2-highcpu-4   = 4
    e2-highcpu-8   = 8
    e2-highcpu-16  = 16
    e2-highcpu-32  = 32
  }

  estimated_total_vcpus = (
    lookup(local.machine_type_vcpus, var.lb_machine_type, 0) +
    lookup(local.machine_type_vcpus, local.effective_api_machine_type, 0) * var.api_count +
    lookup(local.machine_type_vcpus, local.effective_realtime_machine_type, 0) * local.effective_realtime_count +
    lookup(local.machine_type_vcpus, var.mysql_machine_type, 0) +
    lookup(local.machine_type_vcpus, var.redis_machine_type, 0) +
    lookup(local.machine_type_vcpus, var.k6_machine_type, 0) * var.k6_worker_count +
    (var.enable_monitoring ? lookup(local.machine_type_vcpus, var.monitoring_machine_type, 0) : 0)
  )

  estimated_total_ssd_gb = (
    var.lb_disk_size_gb +
    var.app_disk_size_gb * (var.api_count + local.effective_realtime_count) +
    var.mysql_disk_size_gb +
    var.redis_disk_size_gb +
    var.k6_disk_size_gb * var.k6_worker_count +
    (var.enable_monitoring ? var.monitoring_disk_size_gb : 0)
  )
}

data "archive_file" "source" {
  type        = "zip"
  source_dir  = abspath("${path.module}/../..")
  output_path = "/tmp/openchat-source-${var.run_id}.zip"

  excludes = [
    ".git",
    ".gradle",
    ".idea",
    ".terraform",
    "build",
    "out",
    "data",
    "k6/results",
    "*.tfstate",
    "*.tfstate.*",
    "terraform.tfvars",
    "*.hprof",
    "*.rdb",
    ".env",
    ".env.local",
    ".env.*.local"
  ]
}

resource "google_storage_bucket" "results" {
  name                        = local.bucket_name
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = false

  labels = local.bucket_labels

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_storage_bucket_object" "source" {
  name   = local.source_object_name
  bucket = google_storage_bucket.results.name
  source = data.archive_file.source.output_path
}

resource "google_service_account" "runner" {
  account_id   = replace(substr(local.name_prefix, 0, 30), "/-+$/", "")
  display_name = "OpenChat loadtest runner ${var.run_id}"
}

resource "google_storage_bucket_iam_member" "runner_bucket_object_admin" {
  bucket = google_storage_bucket.results.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.runner.email}"
}

resource "google_project_iam_member" "runner_compute_instance_admin" {
  project = var.project_id
  role    = "roles/compute.instanceAdmin.v1"
  member  = "serviceAccount:${google_service_account.runner.email}"
}

resource "google_compute_network" "network" {
  name                    = "${local.name_prefix}-net"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subnet" {
  name          = "${local.name_prefix}-subnet"
  ip_cidr_range = var.subnet_cidr
  region        = var.region
  network       = google_compute_network.network.id
}

resource "google_compute_address" "grafana" {
  name   = "openchat-loadtest-grafana-ip"
  region = var.region

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_compute_router" "router" {
  name    = "${local.name_prefix}-router"
  region  = var.region
  network = google_compute_network.network.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "${local.name_prefix}-nat"
  router                             = google_compute_router.router.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

resource "google_compute_firewall" "lb_from_k6" {
  name    = "${local.name_prefix}-lb-from-k6"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  source_tags = ["openchat-k6", "openchat-monitoring"]
  target_tags = ["openchat-lb"]
}

resource "google_compute_firewall" "app_from_lb_and_k6" {
  name    = "${local.name_prefix}-app-from-lb-k6"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  source_tags = ["openchat-lb", "openchat-k6", "openchat-monitoring"]
  target_tags = ["openchat-app"]
}

resource "google_compute_firewall" "mysql_from_app" {
  name    = "${local.name_prefix}-mysql-from-app"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["3306"]
  }

  source_tags = ["openchat-app", "openchat-k6", "openchat-monitoring"]
  target_tags = ["openchat-mysql"]
}

resource "google_compute_firewall" "redis_from_app" {
  name    = "${local.name_prefix}-redis-from-app"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["6379"]
  }

  source_tags = ["openchat-app", "openchat-monitoring"]
  target_tags = ["openchat-redis"]
}

resource "google_compute_firewall" "influxdb_from_k6" {
  name    = "${local.name_prefix}-influxdb-from-k6"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["8086"]
  }

  source_tags = ["openchat-k6"]
  target_tags = ["openchat-monitoring"]
}

resource "google_compute_firewall" "prometheus_from_k6" {
  name    = "${local.name_prefix}-prometheus-from-k6"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["9090"]
  }

  source_tags = ["openchat-k6"]
  target_tags = ["openchat-monitoring"]
}

resource "google_compute_firewall" "grafana" {
  count   = var.enable_monitoring && length(var.grafana_source_ranges) > 0 ? 1 : 0
  name    = "${local.name_prefix}-grafana"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["3000"]
  }

  source_ranges = var.grafana_source_ranges
  target_tags   = ["openchat-monitoring"]
}

resource "google_compute_firewall" "ssh" {
  count   = length(var.ssh_source_ranges) > 0 ? 1 : 0
  name    = "${local.name_prefix}-ssh"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = var.ssh_source_ranges
  target_tags   = ["openchat-lb", "openchat-app", "openchat-mysql", "openchat-redis", "openchat-k6", "openchat-monitoring"]
}

resource "google_compute_instance" "mysql" {
  name         = local.mysql_vm_name
  machine_type = var.mysql_machine_type
  zone         = var.zone
  tags         = ["openchat-mysql"]
  labels       = merge(local.common_labels, { role = "mysql" })

  boot_disk {
    initialize_params {
      image = var.vm_image
      size  = var.mysql_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/mysql-startup.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
    })
    shutdown-script = templatefile("${path.module}/templates/vm-shutdown.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
      role        = "mysql"
    })
  }

  depends_on = [
    google_compute_router_nat.nat,
    google_storage_bucket_iam_member.runner_bucket_object_admin
  ]
}

resource "google_compute_instance" "redis" {
  name         = local.redis_vm_name
  machine_type = var.redis_machine_type
  zone         = var.zone
  tags         = ["openchat-redis"]
  labels       = merge(local.common_labels, { role = "redis" })

  boot_disk {
    initialize_params {
      image = var.vm_image
      size  = var.redis_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/redis-startup.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
    })
    shutdown-script = templatefile("${path.module}/templates/vm-shutdown.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
      role        = "redis"
    })
  }

  depends_on = [
    google_compute_router_nat.nat,
    google_storage_bucket_iam_member.runner_bucket_object_admin
  ]
}

resource "google_compute_instance" "api" {
  count        = var.api_count
  name         = "${local.name_prefix}-api-${count.index + 1}"
  machine_type = local.effective_api_machine_type
  zone         = var.zone
  tags         = ["openchat-app"]
  labels       = merge(local.common_labels, { role = "api", app_index = tostring(count.index + 1) })

  boot_disk {
    initialize_params {
      image = var.vm_image
      size  = var.app_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/app-startup.sh.tftpl", {
      bucket_name                                                = google_storage_bucket.results.name
      source_object                                              = google_storage_bucket_object.source.name
      run_id                                                     = var.run_id
      app_role                                                   = "api"
      app_index                                                  = count.index + 1
      spring_jpa_hibernate_ddl_auto                              = count.index == 0 ? "update" : "validate"
      schema_initializer                                         = count.index == 0 ? "true" : "false"
      schema_ready_object                                        = "runs/${var.run_id}/workers/schema.ready"
      mysql_ip                                                   = google_compute_instance.mysql.network_interface[0].network_ip
      redis_ip                                                   = google_compute_instance.redis.network_interface[0].network_ip
      websocket_broadcast_lanes                                  = var.websocket_broadcast_lanes
      live_publish_threads                                       = var.live_publish_threads
      room_shard_enabled                                         = var.room_shard_enabled ? "true" : "false"
      room_shard_shard_count                                     = var.room_shard_shard_count
      room_shard_owned_shards                                    = tostring(count.index % var.room_shard_shard_count)
      room_shard_legacy_subscribe_enabled                        = var.room_shard_legacy_subscribe_enabled ? "true" : "false"
      room_partition_enabled                                     = var.room_partition_enabled ? "true" : "false"
      room_partition_partition_count                             = var.room_partition_partition_count
      room_partition_owned_partitions                            = tostring(count.index % var.room_partition_partition_count)
      room_partition_hot_tier_threshold                          = var.room_partition_hot_tier_threshold
      room_partition_max_partitions_per_room                     = var.room_partition_max_partitions_per_room
      room_partition_admin_api_enabled                           = var.room_partition_admin_api_enabled ? "true" : "false"
      room_partition_assignment_enabled                          = var.room_partition_assignment_enabled ? "true" : "false"
      room_partition_assignment_dynamic_subscribe_enabled        = var.room_partition_assignment_dynamic_subscribe_enabled ? "true" : "false"
      room_partition_assignment_node_drain_enabled               = var.room_partition_assignment_node_drain_enabled ? "true" : "false"
      room_partition_assignment_node_drain_readiness_timeout_ms  = var.room_partition_assignment_node_drain_readiness_timeout_ms
      room_partition_lifecycle_enabled                           = var.room_partition_lifecycle_enabled ? "true" : "false"
      room_partition_lifecycle_interval_ms                       = var.room_partition_lifecycle_interval_ms
      room_partition_lifecycle_scale_up_stable_window_ms         = var.room_partition_lifecycle_scale_up_stable_window_ms
      room_partition_lifecycle_scale_up_min_observations         = var.room_partition_lifecycle_scale_up_min_observations
      room_partition_lifecycle_scale_up_cooldown_ms              = var.room_partition_lifecycle_scale_up_cooldown_ms
      room_partition_lifecycle_scale_down_stable_window_ms       = var.room_partition_lifecycle_scale_down_stable_window_ms
      room_partition_lifecycle_scale_down_min_observations       = var.room_partition_lifecycle_scale_down_min_observations
      room_partition_lifecycle_scale_down_cooldown_ms            = var.room_partition_lifecycle_scale_down_cooldown_ms
      room_partition_lifecycle_scale_down_min_partition_age_ms   = var.room_partition_lifecycle_scale_down_min_partition_age_ms
      room_partition_lifecycle_drain_complete_empty_observations = var.room_partition_lifecycle_drain_complete_empty_observations
      room_partition_lifecycle_drain_reconnect_retry_after_ms    = var.room_partition_lifecycle_drain_reconnect_retry_after_ms
      room_scale_pod_work_budget_delivery_per_sec                = var.room_scale_pod_work_budget_delivery_per_sec
      realtime_workload_publish_enabled                          = "false"
      realtime_workload_summary_enabled                          = var.realtime_workload_summary_enabled ? "true" : "false"
      realtime_workload_publish_interval_ms                      = var.realtime_workload_publish_interval_ms
      realtime_workload_snapshot_ttl_ms                          = var.realtime_workload_snapshot_ttl_ms
      realtime_workload_top_room_limit                           = var.realtime_workload_top_room_limit
    })
    shutdown-script = templatefile("${path.module}/templates/vm-shutdown.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
      role        = "api"
    })
  }

  depends_on = [
    google_compute_router_nat.nat,
    google_compute_firewall.mysql_from_app,
    google_compute_firewall.redis_from_app,
    google_storage_bucket_iam_member.runner_bucket_object_admin,
    google_storage_bucket_object.source,
    google_compute_instance.mysql,
    google_compute_instance.redis
  ]
}

resource "google_compute_instance" "realtime" {
  count        = local.effective_realtime_count
  name         = "${local.name_prefix}-realtime-${count.index + 1}"
  machine_type = local.effective_realtime_machine_type
  zone         = var.zone
  tags         = ["openchat-app"]
  labels       = merge(local.common_labels, { role = "realtime", app_index = tostring(count.index + 1) })

  boot_disk {
    initialize_params {
      image = var.vm_image
      size  = var.app_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/app-startup.sh.tftpl", {
      bucket_name                                                = google_storage_bucket.results.name
      source_object                                              = google_storage_bucket_object.source.name
      run_id                                                     = var.run_id
      app_role                                                   = "realtime"
      app_index                                                  = count.index + 1
      spring_jpa_hibernate_ddl_auto                              = "validate"
      schema_initializer                                         = "false"
      schema_ready_object                                        = "runs/${var.run_id}/workers/schema.ready"
      mysql_ip                                                   = google_compute_instance.mysql.network_interface[0].network_ip
      redis_ip                                                   = google_compute_instance.redis.network_interface[0].network_ip
      websocket_broadcast_lanes                                  = var.websocket_broadcast_lanes
      live_publish_threads                                       = var.live_publish_threads
      room_shard_enabled                                         = var.room_shard_enabled ? "true" : "false"
      room_shard_shard_count                                     = var.room_shard_shard_count
      room_shard_owned_shards                                    = tostring(count.index % var.room_shard_shard_count)
      room_shard_legacy_subscribe_enabled                        = var.room_shard_legacy_subscribe_enabled ? "true" : "false"
      room_partition_enabled                                     = var.room_partition_enabled ? "true" : "false"
      room_partition_partition_count                             = var.room_partition_partition_count
      room_partition_owned_partitions                            = tostring(count.index % var.room_partition_partition_count)
      room_partition_hot_tier_threshold                          = var.room_partition_hot_tier_threshold
      room_partition_max_partitions_per_room                     = var.room_partition_max_partitions_per_room
      room_partition_admin_api_enabled                           = var.room_partition_admin_api_enabled ? "true" : "false"
      room_partition_assignment_enabled                          = var.room_partition_assignment_enabled ? "true" : "false"
      room_partition_assignment_dynamic_subscribe_enabled        = var.room_partition_assignment_dynamic_subscribe_enabled ? "true" : "false"
      room_partition_assignment_node_drain_enabled               = var.room_partition_assignment_node_drain_enabled ? "true" : "false"
      room_partition_assignment_node_drain_readiness_timeout_ms  = var.room_partition_assignment_node_drain_readiness_timeout_ms
      room_partition_lifecycle_enabled                           = var.room_partition_lifecycle_enabled ? "true" : "false"
      room_partition_lifecycle_interval_ms                       = var.room_partition_lifecycle_interval_ms
      room_partition_lifecycle_scale_up_stable_window_ms         = var.room_partition_lifecycle_scale_up_stable_window_ms
      room_partition_lifecycle_scale_up_min_observations         = var.room_partition_lifecycle_scale_up_min_observations
      room_partition_lifecycle_scale_up_cooldown_ms              = var.room_partition_lifecycle_scale_up_cooldown_ms
      room_partition_lifecycle_scale_down_stable_window_ms       = var.room_partition_lifecycle_scale_down_stable_window_ms
      room_partition_lifecycle_scale_down_min_observations       = var.room_partition_lifecycle_scale_down_min_observations
      room_partition_lifecycle_scale_down_cooldown_ms            = var.room_partition_lifecycle_scale_down_cooldown_ms
      room_partition_lifecycle_scale_down_min_partition_age_ms   = var.room_partition_lifecycle_scale_down_min_partition_age_ms
      room_partition_lifecycle_drain_complete_empty_observations = var.room_partition_lifecycle_drain_complete_empty_observations
      room_partition_lifecycle_drain_reconnect_retry_after_ms    = var.room_partition_lifecycle_drain_reconnect_retry_after_ms
      room_scale_pod_work_budget_delivery_per_sec                = var.room_scale_pod_work_budget_delivery_per_sec
      realtime_workload_publish_enabled                          = var.realtime_workload_publish_enabled ? "true" : "false"
      realtime_workload_summary_enabled                          = "false"
      realtime_workload_publish_interval_ms                      = var.realtime_workload_publish_interval_ms
      realtime_workload_snapshot_ttl_ms                          = var.realtime_workload_snapshot_ttl_ms
      realtime_workload_top_room_limit                           = var.realtime_workload_top_room_limit
    })
    shutdown-script = templatefile("${path.module}/templates/vm-shutdown.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
      role        = "realtime"
    })
  }

  depends_on = [
    google_compute_router_nat.nat,
    google_compute_firewall.mysql_from_app,
    google_compute_firewall.redis_from_app,
    google_storage_bucket_iam_member.runner_bucket_object_admin,
    google_storage_bucket_object.source,
    google_compute_instance.mysql,
    google_compute_instance.redis
  ]
}

resource "google_compute_instance" "lb" {
  name         = local.lb_vm_name
  machine_type = var.lb_machine_type
  zone         = var.zone
  tags         = ["openchat-lb"]
  labels       = merge(local.common_labels, { role = "lb" })

  boot_disk {
    initialize_params {
      image = var.vm_image
      size  = var.lb_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/lb-startup.sh.tftpl", {
      run_id       = var.run_id
      bucket_name  = google_storage_bucket.results.name
      api_ips      = google_compute_instance.api[*].network_interface[0].network_ip
      realtime_ips = google_compute_instance.realtime[*].network_interface[0].network_ip
    })
    shutdown-script = templatefile("${path.module}/templates/vm-shutdown.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
      role        = "lb"
    })
  }

  depends_on = [
    google_compute_router_nat.nat,
    google_compute_firewall.app_from_lb_and_k6,
    google_compute_instance.api,
    google_compute_instance.realtime
  ]
}

resource "google_compute_instance" "monitoring" {
  count        = var.enable_monitoring ? 1 : 0
  name         = local.monitoring_vm_name
  machine_type = var.monitoring_machine_type
  zone         = var.zone
  tags         = ["openchat-monitoring"]
  labels       = merge(local.common_labels, { role = "monitoring" })

  boot_disk {
    initialize_params {
      image = var.vm_image
      size  = var.monitoring_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id

    access_config {
      nat_ip = google_compute_address.grafana.address
    }
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/monitoring-startup.sh.tftpl", {
      run_id         = var.run_id
      bucket_name    = google_storage_bucket.results.name
      source_object  = google_storage_bucket_object.source.name
      lb_internal_ip = google_compute_instance.lb.network_interface[0].network_ip
      api_ips        = google_compute_instance.api[*].network_interface[0].network_ip
      realtime_ips   = google_compute_instance.realtime[*].network_interface[0].network_ip
      mysql_ip       = google_compute_instance.mysql.network_interface[0].network_ip
      redis_ip       = google_compute_instance.redis.network_interface[0].network_ip
    })
    shutdown-script = templatefile("${path.module}/templates/vm-shutdown.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
      role        = "monitoring"
    })
  }

  depends_on = [
    google_compute_router_nat.nat,
    google_compute_firewall.lb_from_k6,
    google_compute_firewall.app_from_lb_and_k6,
    google_compute_firewall.mysql_from_app,
    google_compute_firewall.redis_from_app,
    google_compute_firewall.influxdb_from_k6,
    google_storage_bucket_iam_member.runner_bucket_object_admin,
    google_storage_bucket_object.source,
    google_compute_instance.lb
  ]
}

resource "google_compute_instance" "k6" {
  count                     = var.k6_worker_count
  name                      = local.k6_vm_names[count.index]
  machine_type              = var.k6_machine_type
  zone                      = var.zone
  allow_stopping_for_update = true
  tags                      = ["openchat-k6"]
  labels                    = merge(local.common_labels, { role = "k6", worker_index = tostring(count.index + 1) })

  boot_disk {
    initialize_params {
      image = var.vm_image
      size  = var.k6_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/k6-startup.sh.tftpl", {
      project_id                              = var.project_id
      zone                                    = var.zone
      lb_vm_name                              = google_compute_instance.lb.name
      mysql_vm_name                           = google_compute_instance.mysql.name
      mysql_internal_ip                       = google_compute_instance.mysql.network_interface[0].network_ip
      redis_vm_name                           = google_compute_instance.redis.name
      app_vm_names                            = join(" ", concat(google_compute_instance.api[*].name, google_compute_instance.realtime[*].name))
      k6_vm_name                              = local.k6_vm_names[count.index]
      k6_vm_names                             = join(" ", local.k6_vm_names)
      k6_worker_index                         = count.index + 1
      k6_worker_count                         = var.k6_worker_count
      k6_is_coordinator                       = count.index == 0 ? "true" : "false"
      k6_cleanup_enabled                      = var.k6_cleanup_enabled ? "true" : "false"
      monitoring_vm_name                      = var.enable_monitoring ? google_compute_instance.monitoring[0].name : ""
      monitoring_internal_ip                  = var.enable_monitoring ? google_compute_instance.monitoring[0].network_interface[0].network_ip : ""
      enable_monitoring                       = var.enable_monitoring ? "true" : "false"
      lb_internal_ip                          = google_compute_instance.lb.network_interface[0].network_ip
      app_internal_ips                        = join(" ", concat(google_compute_instance.api[*].network_interface[0].network_ip, google_compute_instance.realtime[*].network_interface[0].network_ip))
      bucket_name                             = google_storage_bucket.results.name
      source_object                           = google_storage_bucket_object.source.name
      run_id                                  = var.run_id
      profile                                 = var.profile
      vus_list                                = var.vus_list
      chat_duration_seconds                   = var.chat_duration_seconds
      send_interval_ms                        = var.send_interval_ms
      k6_ws_send_stop_after_seconds           = var.k6_ws_send_stop_after_seconds > 0 ? var.k6_ws_send_stop_after_seconds : var.chat_duration_seconds
      k6_chat_ack_p95_threshold_ms            = var.k6_chat_ack_p95_threshold_ms
      k6_visible_freshness_p95_threshold_ms   = var.k6_visible_freshness_p95_threshold_ms
      k6_assignment_preflight_enabled         = var.k6_assignment_preflight_enabled ? "true" : "false"
      k6_assignment_preflight_partition_count = var.room_partition_partition_count
      k6_assignment_preflight_expected_nodes  = var.k6_assignment_preflight_expected_nodes
      k6_assignment_preflight_timeout_seconds = var.k6_assignment_preflight_timeout_seconds
      k6_node_drain_enabled                   = var.k6_node_drain_enabled ? "true" : "false"
      k6_node_drain_after_seconds             = var.k6_node_drain_after_seconds
      k6_node_drain_limit                     = var.k6_node_drain_limit
      k6_node_drain_retry_after_ms            = var.k6_node_drain_retry_after_ms
      realtime_workload_summary_enabled       = var.realtime_workload_summary_enabled ? "true" : "false"
      connect_ramp_seconds                    = var.connect_ramp_seconds
      hot_rooms                               = var.hot_rooms
      vus_per_room                            = var.vus_per_room
      scenario                                = var.scenario
      shared_room_mode                        = var.shared_room_mode ? "true" : "false"
      k6_sender_ratio                         = var.k6_sender_ratio
      k6_observer_ratio                       = var.k6_observer_ratio
      k6_validator_ratio                      = var.k6_validator_ratio
      observer_send_interval_ms               = var.observer_send_interval_ms
      active_user_ratio                       = var.active_user_ratio
      active_heartbeat_interval_ms            = var.active_heartbeat_interval_ms
      passive_settle_ms                       = var.passive_settle_ms
      mixed_hot_room_count                    = var.mixed_hot_room_count
      mixed_hot_room_vus                      = var.mixed_hot_room_vus
      mixed_hot_active_ratio                  = var.mixed_hot_active_ratio
      mixed_hot_send_interval_ms              = var.mixed_hot_send_interval_ms
      mixed_large_room_count                  = var.mixed_large_room_count
      mixed_large_room_vus                    = var.mixed_large_room_vus
      mixed_large_active_ratio                = var.mixed_large_active_ratio
      mixed_large_send_interval_ms            = var.mixed_large_send_interval_ms
      mixed_medium_room_count                 = var.mixed_medium_room_count
      mixed_medium_room_vus                   = var.mixed_medium_room_vus
      mixed_medium_active_ratio               = var.mixed_medium_active_ratio
      mixed_medium_send_interval_ms           = var.mixed_medium_send_interval_ms
      mixed_small_room_count                  = var.mixed_small_room_count
      mixed_small_room_vus                    = var.mixed_small_room_vus
      mixed_small_active_ratio                = var.mixed_small_active_ratio
      mixed_small_send_interval_ms            = var.mixed_small_send_interval_ms
    })
  }

  depends_on = [
    google_compute_router_nat.nat,
    google_project_iam_member.runner_compute_instance_admin,
    google_compute_firewall.lb_from_k6,
    google_compute_firewall.app_from_lb_and_k6,
    google_compute_firewall.influxdb_from_k6,
    google_compute_instance.monitoring,
    google_compute_instance.lb
  ]
}
