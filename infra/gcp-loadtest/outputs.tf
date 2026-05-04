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
  value = concat(google_compute_instance.api[*].name, google_compute_instance.realtime[*].name)
}

output "api_vm_names" {
  value = google_compute_instance.api[*].name
}

output "realtime_vm_names" {
  value = google_compute_instance.realtime[*].name
}

output "mysql_vm_name" {
  value = google_compute_instance.mysql.name
}

output "redis_vm_name" {
  value = google_compute_instance.redis.name
}

output "k6_vm_name" {
  value = google_compute_instance.k6[0].name
}

output "k6_vm_names" {
  value = google_compute_instance.k6[*].name
}

output "monitoring_vm_name" {
  value = var.enable_monitoring ? google_compute_instance.monitoring[0].name : ""
}

output "grafana_static_ip" {
  value = google_compute_address.grafana.address
}

output "grafana_url" {
  value = var.enable_monitoring ? "http://${google_compute_address.grafana.address}:3000" : ""
}

output "lb_internal_url" {
  value = "http://${google_compute_instance.lb.network_interface[0].network_ip}:8080"
}

output "app_internal_urls" {
  value = concat(
    [for app in google_compute_instance.api : "http://${app.network_interface[0].network_ip}:8080"],
    [for app in google_compute_instance.realtime : "http://${app.network_interface[0].network_ip}:8080"]
  )
}

output "api_internal_urls" {
  value = [for app in google_compute_instance.api : "http://${app.network_interface[0].network_ip}:8080"]
}

output "realtime_internal_urls" {
  value = [for app in google_compute_instance.realtime : "http://${app.network_interface[0].network_ip}:8080"]
}

output "estimated_total_vcpus" {
  description = "Estimated Compute Engine vCPU quota consumed by this run for known E2 machine types."
  value       = local.estimated_total_vcpus
}

output "estimated_total_ssd_gb" {
  description = "Estimated regional SSD boot disk quota consumed by this run."
  value       = local.estimated_total_ssd_gb
}
