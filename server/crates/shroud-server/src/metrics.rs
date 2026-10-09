//! Lightweight Prometheus-compatible metrics (text exposition).
//!
//! Human: Latency, pool health, and request volume without a heavy telemetry stack.
//! Agent: Process-local atomics; scrape `GET /operator/metrics` on the operator listener (bearer
//! `OPERATOR_TOKEN`). Not on the public port.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

/// Global counters / gauges updated by middleware and background tasks.
#[derive(Default)]
pub struct Metrics {
    pub http_requests_total: AtomicU64,
    pub http_errors_total: AtomicU64,
    pub ws_connections: AtomicU64,
    pub messages_sent_total: AtomicU64,
    pub media_puts_total: AtomicU64,
    pub media_gets_total: AtomicU64,
    /// Media store calls that failed (not counting blobs that were simply absent).
    pub media_store_errors_total: AtomicU64,
    /// Reads served from the pre-Nebular local volume; drops to zero once it is moved.
    pub media_legacy_reads_total: AtomicU64,
    /// Blobs moved from the pre-Nebular local volume into Nebular.
    pub media_migrated_total: AtomicU64,
    pub calls_created_total: AtomicU64,
    pub started_at: std::sync::OnceLock<Instant>,
}

impl Metrics {
    pub fn new() -> Self {
        let m = Self::default();
        let _ = m.started_at.set(Instant::now());
        m
    }

    pub fn inc_http_ok(&self) {
        self.http_requests_total.fetch_add(1, Ordering::Relaxed);
    }

    pub fn inc_http_err(&self) {
        self.http_requests_total.fetch_add(1, Ordering::Relaxed);
        self.http_errors_total.fetch_add(1, Ordering::Relaxed);
    }

    /// Prometheus text format body.
    pub fn render(&self) -> String {
        let uptime = self
            .started_at
            .get()
            .map(|t| t.elapsed().as_secs())
            .unwrap_or(0);
        format!(
            r#"# HELP shroud_up 1 if the process is serving.
# TYPE shroud_up gauge
shroud_up 1
# HELP shroud_uptime_seconds Seconds since process start.
# TYPE shroud_uptime_seconds counter
shroud_uptime_seconds {uptime}
# HELP shroud_http_requests_total Total HTTP requests observed by metrics middleware.
# TYPE shroud_http_requests_total counter
shroud_http_requests_total {}
# HELP shroud_http_errors_total HTTP responses with status >= 500.
# TYPE shroud_http_errors_total counter
shroud_http_errors_total {}
# HELP shroud_ws_connections Current authenticated WebSocket connections (approximate).
# TYPE shroud_ws_connections gauge
shroud_ws_connections {}
# HELP shroud_messages_sent_total Successful message inserts.
# TYPE shroud_messages_sent_total counter
shroud_messages_sent_total {}
# HELP shroud_media_puts_total Media content PUT successes.
# TYPE shroud_media_puts_total counter
shroud_media_puts_total {}
# HELP shroud_media_gets_total Media content GET successes.
# TYPE shroud_media_gets_total counter
shroud_media_gets_total {}
# HELP shroud_media_store_errors_total Media store calls that failed (absent blobs not counted).
# TYPE shroud_media_store_errors_total counter
shroud_media_store_errors_total {}
# HELP shroud_media_legacy_reads_total Media reads served from the pre-Nebular local volume.
# TYPE shroud_media_legacy_reads_total counter
shroud_media_legacy_reads_total {}
# HELP shroud_media_migrated_total Blobs moved from the pre-Nebular local volume into Nebular.
# TYPE shroud_media_migrated_total counter
shroud_media_migrated_total {}
# HELP shroud_calls_created_total Calls created.
# TYPE shroud_calls_created_total counter
shroud_calls_created_total {}
"#,
            self.http_requests_total.load(Ordering::Relaxed),
            self.http_errors_total.load(Ordering::Relaxed),
            self.ws_connections.load(Ordering::Relaxed),
            self.messages_sent_total.load(Ordering::Relaxed),
            self.media_puts_total.load(Ordering::Relaxed),
            self.media_gets_total.load(Ordering::Relaxed),
            self.media_store_errors_total.load(Ordering::Relaxed),
            self.media_legacy_reads_total.load(Ordering::Relaxed),
            self.media_migrated_total.load(Ordering::Relaxed),
            self.calls_created_total.load(Ordering::Relaxed),
        )
    }
}
