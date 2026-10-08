//! Request log lines: an id, the method, the path and the status.
//!
//! This is its own binary so the tracing callsite is first seen with this subscriber.
//! A client address in the query or in forwarding headers must not be on the line.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use axum::body::Body;
use axum::http::{Request, StatusCode};
use shroud_admin::router;
use tower::ServiceExt;

#[test]
fn request_log_carries_the_id_and_not_a_client_address() {
    let buf = Arc::new(Mutex::new(String::new()));
    let subscriber = Capture {
        next: AtomicU64::new(1),
        buf: buf.clone(),
        spans: Mutex::new(HashMap::new()),
        stack: Mutex::new(Vec::new()),
    };
    tracing::subscriber::set_global_default(subscriber).unwrap();
    tracing::callsite::rebuild_interest_cache();

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    runtime.block_on(async {
        let kept = router(None)
            .oneshot(
                Request::builder()
                    .uri("/healthz?client=203.0.113.10")
                    .header("x-request-id", "req_ok-1")
                    .header("x-forwarded-for", "203.0.113.10")
                    .header("forwarded", "for=198.51.100.20")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(kept.status(), StatusCode::OK);
        assert_eq!(
            kept.headers()
                .get("x-request-id")
                .unwrap()
                .to_str()
                .unwrap(),
            "req_ok-1"
        );

        let replaced = router(None)
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .header("x-request-id", "203.0.113.10")
                    .header("x-forwarded-for", "203.0.113.10")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let id = replaced
            .headers()
            .get("x-request-id")
            .unwrap()
            .to_str()
            .unwrap();
        assert_ne!(id, "203.0.113.10");
        assert!(
            (1..=64).contains(&id.len())
                && id
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_'),
            "{id}"
        );
    });

    let text = buf
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .clone();
    assert!(text.contains("req_ok-1"), "{text}");
    assert!(text.contains("GET"), "{text}");
    assert!(text.contains("/healthz"), "{text}");
    assert!(text.contains("status=200"), "{text}");
    assert!(!text.contains("203.0.113.10"), "{text}");
    assert!(!text.contains("198.51.100.20"), "{text}");
    assert!(!text.contains("client="), "{text}");
    assert!(!text.contains('?'), "{text}");
}

struct Capture {
    next: AtomicU64,
    buf: Arc<Mutex<String>>,
    spans: Mutex<HashMap<u64, String>>,
    stack: Mutex<Vec<u64>>,
}

struct Visit<'a>(&'a mut String);

impl tracing::field::Visit for Visit<'_> {
    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
        use std::fmt::Write;
        let _ = write!(self.0, "{}={value:?} ", field.name());
    }
}

impl tracing::Subscriber for Capture {
    fn enabled(&self, metadata: &tracing::Metadata<'_>) -> bool {
        *metadata.level() <= tracing::Level::INFO
    }

    fn new_span(&self, attrs: &tracing::span::Attributes<'_>) -> tracing::span::Id {
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        let mut fields = String::new();
        attrs.record(&mut Visit(&mut fields));
        self.spans
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .insert(id, fields);
        tracing::span::Id::from_u64(id)
    }

    fn record(&self, span: &tracing::span::Id, values: &tracing::span::Record<'_>) {
        let mut extra = String::new();
        values.record(&mut Visit(&mut extra));
        if let Some(fields) = self
            .spans
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .get_mut(&span.into_u64())
        {
            fields.push_str(&extra);
        }
    }

    fn record_follows_from(&self, _span: &tracing::span::Id, _follows: &tracing::span::Id) {}

    fn event(&self, event: &tracing::Event<'_>) {
        let stack = self
            .stack
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .clone();
        let spans = self
            .spans
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        let mut line = String::new();
        for id in &stack {
            if let Some(fields) = spans.get(id) {
                line.push_str(fields);
            }
        }
        drop(spans);
        event.record(&mut Visit(&mut line));
        line.push('\n');
        self.buf
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .push_str(&line);
    }

    fn enter(&self, span: &tracing::span::Id) {
        self.stack
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .push(span.into_u64());
    }

    fn exit(&self, span: &tracing::span::Id) {
        let id = span.into_u64();
        let mut stack = self
            .stack
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if let Some(pos) = stack.iter().rposition(|item| *item == id) {
            stack.remove(pos);
        }
    }
}
