//! Tracing subscriber setup — Ownly-style defaults with category targets.
//!
//! Human: `RUST_LOG` overrides everything. Without it, use a readable multi-crate default.
//! Agent: CALLS once from `run()` before any other work; never logs secrets.

use tracing_subscriber::{EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};

/// Default filter when `RUST_LOG` is unset (local docker / cargo run).
///
/// Matches Ownly’s “default” preset intent: HTTP + app at info, sqlx quieter.
pub fn default_env_filter() -> EnvFilter {
    EnvFilter::new(
        "info,\
         shroud_server=info,\
         shroud_server::routes=info,\
         shroud_server::realtime=info,\
         shroud_server::push=info,\
         tower_http=info,\
         sqlx=warn",
    )
}

/// Install the global tracing subscriber (fmt + env filter).
pub fn init_subscriber() {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| default_env_filter());

    // Human: Prefer JSON when RUST_LOG_FORMAT=json (ops / log aggregators); plain text for local dev.
    let json = std::env::var("RUST_LOG_FORMAT")
        .map(|value| value.eq_ignore_ascii_case("json"))
        .unwrap_or(false);

    if json {
        tracing_subscriber::registry()
            .with(filter)
            .with(tracing_subscriber::fmt::layer().json())
            .init();
    } else {
        tracing_subscriber::registry()
            .with(filter)
            .with(
                tracing_subscriber::fmt::layer()
                    .with_target(true)
                    .with_thread_ids(false)
                    .with_file(false)
                    .with_line_number(false),
            )
            .init();
    }

    tracing::info!(
        rust_log = %std::env::var("RUST_LOG").unwrap_or_else(|_| "(default)".into()),
        format = if json { "json" } else { "text" },
        "tracing subscriber initialized"
    );
}
