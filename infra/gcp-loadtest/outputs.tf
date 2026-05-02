output "run_id" {
  value = var.run_id
}

output "bucket" {
  value = google_storage_bucket.results.name
}

output "result_prefix" {
  value = "gs://${google_storage_bucket.results.name}/runs/${var.run_id}/"
}

output "lb_vm_name" {
  value = google_compute_instance.lb.name
}

output "app_vm_names" {
  value = google_compute_instance.app[*].name
}

output "mysql_vm_name" {
  value = google_compute_instance.mysql.name
}

output "redis_vm_name" {
  value = google_compute_instance.redis.name
}

output "k6_vm_name" {
  value = google_compute_instance.k6.name
}

output "lb_internal_url" {
  value = "http://${google_compute_instance.lb.network_interface[0].network_ip}:8080"
}

output "app_internal_urls" {
  value = [for app in google_compute_instance.app : "http://${app.network_interface[0].network_ip}:8080"]
}

output "estimated_total_vcpus" {
  description = "Estimated Compute Engine vCPU quota consumed by this run for known E2 machine types."
  value       = local.estimated_total_vcpus
}

output "estimated_total_ssd_gb" {
  description = "Estimated regional SSD boot disk quota consumed by this run."
  value       = local.estimated_total_ssd_gb
}
