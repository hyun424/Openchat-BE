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
  name_prefix        = substr("openchat-lt-${local.safe_run_id}", 0, 55)
  sanitized_project  = lower(replace(var.project_id, "/[^a-z0-9-]/", "-"))
  default_bucket     = substr(lower(replace("openchat-loadtest-${local.sanitized_project}", "/[^a-z0-9._-]/", "-")), 0, 63)
  bucket_name        = var.bucket_name != "" ? var.bucket_name : local.default_bucket
  source_object_name = "runs/${var.run_id}/source/openchat-source.zip"

  lb_vm_name    = "${local.name_prefix}-lb"
  mysql_vm_name = "${local.name_prefix}-mysql"
  redis_vm_name = "${local.name_prefix}-redis"
  k6_vm_name    = "${local.name_prefix}-k6"

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
    lookup(local.machine_type_vcpus, var.app_machine_type, 0) * var.app_count +
    lookup(local.machine_type_vcpus, var.mysql_machine_type, 0) +
    lookup(local.machine_type_vcpus, var.redis_machine_type, 0) +
    lookup(local.machine_type_vcpus, var.k6_machine_type, 0)
  )

  estimated_total_ssd_gb = (
    var.lb_disk_size_gb +
    var.app_disk_size_gb * var.app_count +
    var.mysql_disk_size_gb +
    var.redis_disk_size_gb +
    var.k6_disk_size_gb
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
  account_id   = substr(local.name_prefix, 0, 30)
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

resource "google_compute_firewall" "lb_from_k6" {
  name    = "${local.name_prefix}-lb-from-k6"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  source_tags = ["openchat-k6"]
  target_tags = ["openchat-lb"]
}

resource "google_compute_firewall" "app_from_lb_and_k6" {
  name    = "${local.name_prefix}-app-from-lb-k6"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  source_tags = ["openchat-lb", "openchat-k6"]
  target_tags = ["openchat-app"]
}

resource "google_compute_firewall" "mysql_from_app" {
  name    = "${local.name_prefix}-mysql-from-app"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["3306"]
  }

  source_tags = ["openchat-app"]
  target_tags = ["openchat-mysql"]
}

resource "google_compute_firewall" "redis_from_app" {
  name    = "${local.name_prefix}-redis-from-app"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["6379"]
  }

  source_tags = ["openchat-app"]
  target_tags = ["openchat-redis"]
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
  target_tags   = ["openchat-lb", "openchat-app", "openchat-mysql", "openchat-redis", "openchat-k6"]
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
    access_config {}
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
    access_config {}
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
    google_storage_bucket_iam_member.runner_bucket_object_admin
  ]
}

resource "google_compute_instance" "app" {
  count        = var.app_count
  name         = "${local.name_prefix}-app-${count.index + 1}"
  machine_type = var.app_machine_type
  zone         = var.zone
  tags         = ["openchat-app"]
  labels       = merge(local.common_labels, { role = "app", app_index = tostring(count.index + 1) })

  boot_disk {
    initialize_params {
      image = var.vm_image
      size  = var.app_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
    access_config {}
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/app-startup.sh.tftpl", {
      bucket_name               = google_storage_bucket.results.name
      source_object             = google_storage_bucket_object.source.name
      run_id                    = var.run_id
      app_index                 = count.index + 1
      mysql_ip                  = google_compute_instance.mysql.network_interface[0].network_ip
      redis_ip                  = google_compute_instance.redis.network_interface[0].network_ip
      websocket_broadcast_lanes = var.websocket_broadcast_lanes
    })
    shutdown-script = templatefile("${path.module}/templates/vm-shutdown.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
      role        = "app"
    })
  }

  depends_on = [
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
    access_config {}
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/lb-startup.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
      app_ips     = google_compute_instance.app[*].network_interface[0].network_ip
    })
    shutdown-script = templatefile("${path.module}/templates/vm-shutdown.sh.tftpl", {
      run_id      = var.run_id
      bucket_name = google_storage_bucket.results.name
      role        = "lb"
    })
  }

  depends_on = [
    google_compute_firewall.app_from_lb_and_k6,
    google_compute_instance.app
  ]
}

resource "google_compute_instance" "k6" {
  name         = local.k6_vm_name
  machine_type = var.k6_machine_type
  zone         = var.zone
  tags         = ["openchat-k6"]
  labels       = merge(local.common_labels, { role = "k6" })

  boot_disk {
    initialize_params {
      image = var.vm_image
      size  = var.k6_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
    access_config {}
  }

  service_account {
    email  = google_service_account.runner.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    startup-script = templatefile("${path.module}/templates/k6-startup.sh.tftpl", {
      project_id            = var.project_id
      zone                  = var.zone
      lb_vm_name            = google_compute_instance.lb.name
      mysql_vm_name         = google_compute_instance.mysql.name
      redis_vm_name         = google_compute_instance.redis.name
      app_vm_names          = join(" ", google_compute_instance.app[*].name)
      k6_vm_name            = local.k6_vm_name
      lb_internal_ip        = google_compute_instance.lb.network_interface[0].network_ip
      app_internal_ips      = join(" ", google_compute_instance.app[*].network_interface[0].network_ip)
      bucket_name           = google_storage_bucket.results.name
      source_object         = google_storage_bucket_object.source.name
      run_id                = var.run_id
      profile               = var.profile
      vus_list              = var.vus_list
      chat_duration_seconds = var.chat_duration_seconds
      send_interval_ms      = var.send_interval_ms
      connect_ramp_seconds  = var.connect_ramp_seconds
      hot_rooms             = var.hot_rooms
      vus_per_room          = var.vus_per_room
      scenario              = var.scenario
    })
  }

  depends_on = [
    google_project_iam_member.runner_compute_instance_admin,
    google_compute_firewall.lb_from_k6,
    google_compute_firewall.app_from_lb_and_k6,
    google_compute_instance.lb
  ]
}
