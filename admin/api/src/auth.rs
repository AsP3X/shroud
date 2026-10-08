//! Operator bootstrap, setup links, and the session routes in §3.1.
//!
//! A wrong name, password or authenticator code all run one argon2id check and then answer
//! `401 BAD_CREDENTIALS`. Session ids and setup tokens are stored as SHA-256. The authenticator
//! secret is AES-256-GCM ciphertext. Recovery codes are stored as SHA-256 and shown once.

use axum::Json;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{Router, get, post};
use chrono::{DateTime, SecondsFormat, TimeDelta, Utc};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;
use sqlx::Row;
use subtle::ConstantTimeEq;
use uuid::Uuid;

use crate::crypto::{self, normalize_code};
use crate::error::ApiError;
use crate::password;
use crate::state::AppState;
use crate::totp;

const SESSION_COOKIE: &str = "__Host-admin";
const CSRF_COOKIE: &str = "admin_csrf";
const ABSOLUTE_SECS: i64 = 12 * 60 * 60;
const IDLE_SECS: i64 = 30 * 60;
const REAUTH_SECS: i64 = 5 * 60;
const SETUP_TTL_SECS: i64 = 24 * 60 * 60;
const BOOTSTRAP_LOCK: i64 = 0x5348_4442;

#[derive(Debug, thiserror::Error)]
pub enum BootstrapError {
    #[error("Usage: shroud-admin bootstrap [--recover] [--name NAME]")]
    Usage,
    #[error("ADMIN_DATABASE_URL is not set")]
    DatabaseUrl,
    #[error("ADMIN_PUBLIC_URL is not set")]
    PublicUrl,
    #[error("ADMIN_PUBLIC_URL must start with http:// or https://")]
    PublicUrlScheme,
    #[error("the operator name must be 1 to 64 characters")]
    Name,
    #[error("an operator already exists; run shroud-admin bootstrap --recover to re-enrol")]
    Exists,
    #[error("there is no operator to recover")]
    Nobody,
    #[error("more than one operator exists; pass --name")]
    NeedName,
    #[error("no operator is named {0}")]
    NotFound(String),
    #[error("could not connect to the database or migrate it")]
    Database,
}

impl BootstrapError {
    pub fn exit_code(&self) -> i32 {
        match self {
            BootstrapError::Usage => 2,
            _ => 1,
        }
    }
}

struct Opts {
    recover: bool,
    name: Option<String>,
}

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/session", get(get_session).post(sign_in).delete(sign_out))
        .route("/session/recovery", post(recover_session))
        .route("/session/reauth", post(reauth))
        .route("/setup/{token}", get(setup_get).post(setup_post))
}

pub(crate) async fn unknown() -> ApiError {
    ApiError::not_found()
}

/// A signed-in operator. `reauth_until` is absent or past when a write needs a fresh code.
pub(crate) struct SignedIn {
    pub operator_id: Uuid,
    pub role: String,
    pub reauth_until: Option<DateTime<Utc>>,
}

/// A signed-in operator, or the 401 response (cookies cleared when the session was dead).
pub(crate) enum Admission {
    In,
    Out(Response),
}

/// Require a live session. Touches `last_used_at` so paging the console keeps the session.
pub(crate) async fn admit(state: &AppState, headers: &HeaderMap) -> Result<Admission, ApiError> {
    let Some(raw) = cookie(headers, SESSION_COOKIE) else {
        return Ok(Admission::Out(ApiError::unauthenticated().into_response()));
    };
    let pool = db_pool(state)?;
    let Some(session) = load_session(&pool, raw).await? else {
        return Ok(Admission::Out(unauthenticated_clear()));
    };
    if Utc::now() >= expires_at(session.created_at, session.last_used_at) {
        delete_session(&pool, &session.id_hash).await?;
        return Ok(Admission::Out(unauthenticated_clear()));
    }
    touch_session(&pool, &session.id_hash).await?;
    Ok(Admission::In)
}

/// A session plus the CSRF header and cookie. Writes use this; a GET uses [`admit`].
pub(crate) async fn admit_mutation(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<Result<SignedIn, Response>, ApiError> {
    let Some(raw) = cookie(headers, SESSION_COOKIE) else {
        return Ok(Err(ApiError::unauthenticated().into_response()));
    };
    let pool = db_pool(state)?;
    let Some(session) = load_session(&pool, raw).await? else {
        return Ok(Err(unauthenticated_clear()));
    };
    if Utc::now() >= expires_at(session.created_at, session.last_used_at) {
        delete_session(&pool, &session.id_hash).await?;
        return Ok(Err(unauthenticated_clear()));
    }
    if !csrf_matches(headers, &session.csrf) {
        return Err(ApiError::csrf());
    }
    touch_session(&pool, &session.id_hash).await?;
    Ok(Ok(signed_in(session)))
}

pub(crate) fn reauth_current(until: Option<DateTime<Utc>>) -> bool {
    until.is_some_and(|until| until > Utc::now())
}

fn signed_in(session: LiveSession) -> SignedIn {
    SignedIn {
        operator_id: session.operator_id,
        role: session.role,
        reauth_until: session.reauth_until,
    }
}

pub async fn bootstrap_cli(args: impl Iterator<Item = String>) -> Result<(), BootstrapError> {
    let opts = parse_args(args)?;
    super::init_tracing();
    let database = nonempty("ADMIN_DATABASE_URL").ok_or(BootstrapError::DatabaseUrl)?;
    let public = nonempty("ADMIN_PUBLIC_URL").ok_or(BootstrapError::PublicUrl)?;
    let pool = crate::db::connect(&database).await.map_err(bootstrap_db)?;
    crate::db::migrate(&pool).await.map_err(bootstrap_db)?;
    let link = bootstrap(&pool, &public, opts.name.as_deref(), opts.recover).await?;
    println!("{link}");
    eprintln!("This setup link is shown once. Open it to choose a password and an authenticator.");
    Ok(())
}

/// Create the first operator, or re-enrol one. Returns the one-time setup URL.
/// `name` is omitted to create `operator`, or on recover to re-enrol the only operator.
pub async fn bootstrap(
    pool: &PgPool,
    public_url: &str,
    name: Option<&str>,
    recover: bool,
) -> Result<String, BootstrapError> {
    let public_url = clean_public_url(public_url)?;
    let mut tx = pool.begin().await.map_err(bootstrap_db)?;
    sqlx::query("SELECT pg_advisory_xact_lock($1)")
        .bind(BOOTSTRAP_LOCK)
        .execute(&mut *tx)
        .await
        .map_err(bootstrap_db)?;

    let operator_id = if recover {
        recover_operator(&mut tx, name).await?
    } else {
        let name = clean_name(name.unwrap_or("operator"))?;
        let exists: bool = sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM admin.operators)")
            .fetch_one(&mut *tx)
            .await
            .map_err(bootstrap_db)?;
        if exists {
            return Err(BootstrapError::Exists);
        }
        sqlx::query_scalar(
            "INSERT INTO admin.operators (name, role) VALUES ($1, 'write') RETURNING id",
        )
        .bind(&name)
        .fetch_one(&mut *tx)
        .await
        .map_err(bootstrap_db)?
    };

    let token = crypto::random_token();
    let token_hash = crypto::sha256(token.as_bytes());
    let expires = Utc::now() + TimeDelta::seconds(SETUP_TTL_SECS);
    sqlx::query(
        "INSERT INTO admin.setup_links (token_hash, operator_id, expires_at) VALUES ($1, $2, $3)",
    )
    .bind(token_hash.as_slice())
    .bind(operator_id)
    .bind(expires)
    .execute(&mut *tx)
    .await
    .map_err(bootstrap_db)?;
    tx.commit().await.map_err(bootstrap_db)?;
    Ok(format!("{public_url}/setup/{token}"))
}

async fn recover_operator(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    name: Option<&str>,
) -> Result<Uuid, BootstrapError> {
    let rows = sqlx::query("SELECT id, name FROM admin.operators")
        .fetch_all(&mut **tx)
        .await
        .map_err(bootstrap_db)?;
    let mut operators = Vec::with_capacity(rows.len());
    for row in rows {
        let id: Uuid = row.try_get("id").map_err(bootstrap_db)?;
        let operator_name: String = row.try_get("name").map_err(bootstrap_db)?;
        operators.push((id, operator_name));
    }
    let id = match (name, operators.as_slice()) {
        (_, []) => return Err(BootstrapError::Nobody),
        (None, [(id, _)]) => *id,
        (None, _) => return Err(BootstrapError::NeedName),
        (Some(name), ops) => {
            let name = clean_name(name)?;
            ops.iter()
                .find(|(_, operator_name)| operator_name == &name)
                .map(|(id, _)| *id)
                .ok_or(BootstrapError::NotFound(name))?
        }
    };
    sqlx::query(
        "UPDATE admin.operators SET password_hash = NULL, totp_secret_enc = NULL WHERE id = $1",
    )
    .bind(id)
    .execute(&mut **tx)
    .await
    .map_err(bootstrap_db)?;
    sqlx::query("DELETE FROM admin.operator_sessions WHERE operator_id = $1")
        .bind(id)
        .execute(&mut **tx)
        .await
        .map_err(bootstrap_db)?;
    sqlx::query("DELETE FROM admin.recovery_codes WHERE operator_id = $1")
        .bind(id)
        .execute(&mut **tx)
        .await
        .map_err(bootstrap_db)?;
    sqlx::query(
        "UPDATE admin.setup_links SET used_at = now() WHERE operator_id = $1 AND used_at IS NULL",
    )
    .bind(id)
    .execute(&mut **tx)
    .await
    .map_err(bootstrap_db)?;
    Ok(id)
}

fn parse_args(args: impl Iterator<Item = String>) -> Result<Opts, BootstrapError> {
    let mut recover = false;
    let mut name = None;
    let mut args = args.peekable();
    while let Some(arg) = args.next() {
        if arg == "--recover" {
            recover = true;
        } else if arg == "--name" {
            let value = args.next().ok_or(BootstrapError::Usage)?;
            if value.starts_with("--") || value.is_empty() {
                return Err(BootstrapError::Usage);
            }
            name = Some(value);
        } else if let Some(value) = arg.strip_prefix("--name=") {
            if value.is_empty() {
                return Err(BootstrapError::Usage);
            }
            name = Some(value.to_owned());
        } else {
            return Err(BootstrapError::Usage);
        }
    }
    Ok(Opts { recover, name })
}

fn clean_name(name: &str) -> Result<String, BootstrapError> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 64 || name.chars().any(char::is_control) {
        return Err(BootstrapError::Name);
    }
    Ok(name.to_owned())
}

fn clean_public_url(url: &str) -> Result<String, BootstrapError> {
    let url = url.trim().trim_end_matches('/');
    if !(url.starts_with("https://") || url.starts_with("http://")) || url.contains(' ') {
        return Err(BootstrapError::PublicUrlScheme);
    }
    Ok(url.to_owned())
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

fn bootstrap_db(err: sqlx::Error) -> BootstrapError {
    crate::db::log_db("bootstrap", &err);
    if err.as_database_error().and_then(|db| db.code()).as_deref() == Some("23505") {
        BootstrapError::Exists
    } else {
        BootstrapError::Database
    }
}

#[derive(Deserialize)]
struct SignInBody {
    operator: String,
    password: String,
    totp: String,
}

#[derive(Deserialize)]
struct RecoveryBody {
    operator: String,
    password: String,
    recovery_code: String,
}

#[derive(Deserialize)]
struct TotpBody {
    totp: String,
}

#[derive(Deserialize)]
struct SetupBody {
    password: String,
    totp: String,
}

#[derive(Serialize)]
struct SessionBody {
    operator: OperatorBody,
    expires_at: String,
    reauth_until: Option<String>,
}

#[derive(Serialize)]
struct OperatorBody {
    id: String,
    name: String,
    role: String,
}

#[derive(Serialize)]
struct ReauthBody {
    reauth_until: String,
}

#[derive(Serialize)]
struct SetupView {
    operator_name: String,
    totp_uri: String,
    qr_svg: String,
}

#[derive(Serialize)]
struct EnrolledBody {
    recovery_codes: Vec<String>,
}

struct Operator {
    id: Uuid,
    password_hash: Option<String>,
    totp_secret_enc: Option<Vec<u8>>,
    enabled: bool,
}

struct LiveSession {
    id_hash: Vec<u8>,
    operator_id: Uuid,
    name: String,
    role: String,
    csrf: String,
    created_at: DateTime<Utc>,
    last_used_at: DateTime<Utc>,
    reauth_until: Option<DateTime<Utc>>,
}

async fn sign_in(
    State(state): State<AppState>,
    body: Result<Json<SignInBody>, JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::validation("The request body isn't valid."))?;
    check_sign_in_fields(&body.operator, &body.password, &body.totp)?;
    let pool = db_pool(&state)?;
    let key = copy_key(&state)?;
    let limit_key = limit_key(&body.operator);
    let retry = state.limiter().retry_after(&limit_key);
    let operator = fetch_operator(&pool, body.operator.trim()).await?;
    let password_ok = check_password(
        body.password,
        operator.as_ref().and_then(|op| op.password_hash.clone()),
    )
    .await?;
    let secret = operator
        .as_ref()
        .and_then(|op| open_secret(&key, op.totp_secret_enc.as_deref()));
    let totp_ok = secret
        .as_deref()
        .is_some_and(|secret| totp::accept(secret, body.totp.trim(), Utc::now().timestamp()));
    if let Some(secs) = retry {
        return Err(ApiError::rate_limited(secs));
    }
    let allowed = operator.as_ref().is_some_and(|op| op.enabled) & password_ok & totp_ok;
    if !allowed {
        state.limiter().record_failure(&limit_key);
        if let Some(op) = &operator {
            let detail = failure_detail(op.enabled, password_ok);
            write_audit(&pool, op.id, "session.sign_in", "refused", detail).await?;
        }
        tracing::debug!(
            action = "session.sign_in",
            outcome = "refused",
            "operator sign-in refused"
        );
        return Err(ApiError::bad_credentials());
    }
    let op = operator.ok_or_else(ApiError::bad_credentials)?;
    state.limiter().clear(&limit_key);
    let (session_id, csrf) = open_session(&pool, op.id, "session.sign_in").await?;
    tracing::info!(
        action = "session.sign_in",
        outcome = "ok",
        "operator session opened"
    );
    Ok(no_content(&set_cookies(&session_id, &csrf)))
}

async fn recover_session(
    State(state): State<AppState>,
    body: Result<Json<RecoveryBody>, JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::validation("The request body isn't valid."))?;
    check_sign_in_fields(&body.operator, &body.password, &body.recovery_code)?;
    let pool = db_pool(&state)?;
    let limit_key = limit_key(&body.operator);
    let retry = state.limiter().retry_after(&limit_key);
    let operator = fetch_operator(&pool, body.operator.trim()).await?;
    let password_ok = check_password(
        body.password,
        operator.as_ref().and_then(|op| op.password_hash.clone()),
    )
    .await?;
    let matched = match &operator {
        Some(op) => matching_code(&pool, op.id, &body.recovery_code).await?,
        None => {
            let _ = crypto::sha256(normalize_code(&body.recovery_code).as_bytes());
            None
        }
    };
    if let Some(secs) = retry {
        return Err(ApiError::rate_limited(secs));
    }
    let allowed = operator.as_ref().is_some_and(|op| op.enabled) & password_ok & matched.is_some();
    if !allowed {
        state.limiter().record_failure(&limit_key);
        if let Some(op) = &operator {
            let detail = if !op.enabled {
                Some("Disabled")
            } else if !password_ok {
                Some("Wrong password")
            } else {
                Some("Wrong recovery code")
            };
            write_audit(&pool, op.id, "session.recovery", "refused", detail).await?;
        }
        return Err(ApiError::bad_credentials());
    }
    let op = operator.ok_or_else(ApiError::bad_credentials)?;
    let code_hash = matched.ok_or_else(ApiError::bad_credentials)?;
    burn_code(&pool, op.id, &code_hash).await?;
    state.limiter().clear(&limit_key);
    let (session_id, csrf) = open_session(&pool, op.id, "session.recovery").await?;
    Ok(no_content(&set_cookies(&session_id, &csrf)))
}

async fn get_session(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let Some(raw) = cookie(&headers, SESSION_COOKIE) else {
        return Err(ApiError::unauthenticated());
    };
    let pool = db_pool(&state)?;
    let Some(session) = load_session(&pool, raw).await? else {
        return Ok(unauthenticated_clear());
    };
    if Utc::now() >= expires_at(session.created_at, session.last_used_at) {
        delete_session(&pool, &session.id_hash).await?;
        return Ok(unauthenticated_clear());
    }
    let last_used = touch_session(&pool, &session.id_hash).await?;
    let reauth = session
        .reauth_until
        .filter(|until| *until > Utc::now())
        .map(stamp);
    Ok(Json(SessionBody {
        operator: OperatorBody {
            id: session.operator_id.to_string(),
            name: session.name,
            role: session.role,
        },
        expires_at: stamp(expires_at(session.created_at, last_used)),
        reauth_until: reauth,
    })
    .into_response())
}

async fn sign_out(State(state): State<AppState>, headers: HeaderMap) -> Result<Response, ApiError> {
    let Some(raw) = cookie(&headers, SESSION_COOKIE) else {
        return Ok(no_content(&clear_cookies()));
    };
    let Ok(pool) = pool_result(&state) else {
        return Ok(no_content(&clear_cookies()));
    };
    let Some(session) = load_session(&pool, raw).await? else {
        return Ok(no_content(&clear_cookies()));
    };
    if !csrf_matches(&headers, &session.csrf) {
        return Err(ApiError::csrf());
    }
    delete_session(&pool, &session.id_hash).await?;
    write_audit(&pool, session.operator_id, "session.sign_out", "ok", None).await?;
    Ok(no_content(&clear_cookies()))
}

async fn reauth(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Result<Json<TotpBody>, JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::validation("The request body isn't valid."))?;
    let pool = db_pool(&state)?;
    let key = copy_key(&state)?;
    let raw = cookie(&headers, SESSION_COOKIE).ok_or_else(ApiError::unauthenticated)?;
    let session = load_session(&pool, raw)
        .await?
        .ok_or_else(ApiError::unauthenticated)?;
    if Utc::now() >= expires_at(session.created_at, session.last_used_at) {
        delete_session(&pool, &session.id_hash).await?;
        return Ok(unauthenticated_clear());
    }
    if !csrf_matches(&headers, &session.csrf) {
        return Err(ApiError::csrf());
    }
    let limit_key = limit_key(&session.name);
    if let Some(secs) = state.limiter().retry_after(&limit_key) {
        return Err(ApiError::rate_limited(secs));
    }
    let secret = operator_secret(&pool, &key, session.operator_id).await?;
    let ok = secret
        .as_deref()
        .is_some_and(|secret| totp::accept(secret, body.totp.trim(), Utc::now().timestamp()));
    if !ok {
        state.limiter().record_failure(&limit_key);
        write_audit(
            &pool,
            session.operator_id,
            "session.sign_in",
            "refused",
            Some("Wrong code"),
        )
        .await?;
        return Err(ApiError::bad_credentials());
    }
    state.limiter().clear(&limit_key);
    let until = Utc::now() + TimeDelta::seconds(REAUTH_SECS);
    sqlx::query("UPDATE admin.operator_sessions SET reauth_until = $2, last_used_at = now() WHERE id_hash = $1")
        .bind(&session.id_hash)
        .bind(until)
        .execute(&pool)
        .await
        .map_err(db_err)?;
    Ok(Json(ReauthBody {
        reauth_until: stamp(until),
    })
    .into_response())
}

async fn setup_get(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> Result<Response, ApiError> {
    let pool = db_pool(&state)?;
    let key = copy_key(&state)?;
    let link = open_link(&pool, &token).await?;
    let secret = ensure_secret(&pool, &key, link.operator_id).await?;
    let uri = totp::uri(&link.name, &secret);
    let qr_svg = totp::qr_svg(&uri).map_err(|_| ApiError::internal())?;
    Ok(Json(SetupView {
        operator_name: link.name,
        totp_uri: uri,
        qr_svg,
    })
    .into_response())
}

async fn setup_post(
    State(state): State<AppState>,
    Path(token): Path<String>,
    body: Result<Json<SetupBody>, JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::validation("The request body isn't valid."))?;
    let pool = db_pool(&state)?;
    let key = copy_key(&state)?;
    let link = open_link(&pool, &token).await?;
    if let Err(message) = password::acceptable(&body.password) {
        return Err(ApiError::validation(message));
    }
    let limit_key = format!("setup:{}", link.operator_id);
    if let Some(secs) = state.limiter().retry_after(&limit_key) {
        return Err(ApiError::rate_limited(secs));
    }
    let secret = operator_secret(&pool, &key, link.operator_id).await?;
    let Some(secret) = secret else {
        return Err(ApiError::validation(
            "Open the setup link before choosing a password.",
        ));
    };
    if !totp::accept(&secret, body.totp.trim(), Utc::now().timestamp()) {
        state.limiter().record_failure(&limit_key);
        write_audit(
            &pool,
            link.operator_id,
            "setup.complete",
            "refused",
            Some("Wrong code"),
        )
        .await?;
        return Err(ApiError::bad_credentials());
    }
    let password_hash = hash_password(body.password).await?;
    let codes = issue_codes(&pool, &link, &password_hash).await?;
    state.limiter().clear(&limit_key);
    Ok(Json(EnrolledBody {
        recovery_codes: codes,
    })
    .into_response())
}

struct SetupLink {
    operator_id: Uuid,
    name: String,
    token_hash: Vec<u8>,
}

async fn open_link(pool: &PgPool, token: &str) -> Result<SetupLink, ApiError> {
    if token.is_empty() || token.len() > 256 {
        return Err(ApiError::link_unknown());
    }
    let token_hash = crypto::sha256(token.as_bytes());
    let row = sqlx::query(
        "SELECT l.operator_id, l.expires_at, l.used_at, o.name FROM admin.setup_links l
         JOIN admin.operators o ON o.id = l.operator_id WHERE l.token_hash = $1",
    )
    .bind(token_hash.as_slice())
    .fetch_optional(pool)
    .await
    .map_err(db_err)?;
    let Some(row) = row else {
        return Err(ApiError::link_unknown());
    };
    let used_at: Option<DateTime<Utc>> = row.try_get("used_at").map_err(db_err)?;
    let expires_at: DateTime<Utc> = row.try_get("expires_at").map_err(db_err)?;
    if used_at.is_some() {
        return Err(ApiError::link_used());
    }
    if expires_at <= Utc::now() {
        return Err(ApiError::link_unknown());
    }
    Ok(SetupLink {
        operator_id: row.try_get("operator_id").map_err(db_err)?,
        name: row.try_get("name").map_err(db_err)?,
        token_hash: token_hash.to_vec(),
    })
}

async fn ensure_secret(
    pool: &PgPool,
    key: &[u8; 32],
    operator_id: Uuid,
) -> Result<Vec<u8>, ApiError> {
    if let Some(existing) = operator_secret(pool, key, operator_id).await? {
        return Ok(existing);
    }
    let fresh = totp::secret();
    let sealed = crypto::seal(key, &fresh).map_err(|_| ApiError::internal())?;
    let updated = sqlx::query(
        "UPDATE admin.operators SET totp_secret_enc = $2 WHERE id = $1 AND totp_secret_enc IS NULL",
    )
    .bind(operator_id)
    .bind(&sealed)
    .execute(pool)
    .await
    .map_err(db_err)?;
    if updated.rows_affected() == 1 {
        return Ok(fresh.to_vec());
    }
    operator_secret(pool, key, operator_id)
        .await?
        .ok_or_else(ApiError::internal)
}

async fn operator_secret(
    pool: &PgPool,
    key: &[u8; 32],
    operator_id: Uuid,
) -> Result<Option<Vec<u8>>, ApiError> {
    let blob: Option<Vec<u8>> =
        sqlx::query_scalar("SELECT totp_secret_enc FROM admin.operators WHERE id = $1")
            .bind(operator_id)
            .fetch_optional(pool)
            .await
            .map_err(db_err)?
            .flatten();
    match blob {
        None => Ok(None),
        Some(blob) => match crypto::open(key, &blob) {
            Ok(secret) => Ok(Some(secret)),
            Err(_) => {
                tracing::error!("could not decrypt an authenticator secret");
                Err(ApiError::internal())
            }
        },
    }
}

async fn issue_codes(
    pool: &PgPool,
    link: &SetupLink,
    password_hash: &str,
) -> Result<Vec<String>, ApiError> {
    let mut tx = pool.begin().await.map_err(db_err)?;
    let row = sqlx::query(
        "SELECT used_at, expires_at FROM admin.setup_links WHERE token_hash = $1 FOR UPDATE",
    )
    .bind(&link.token_hash)
    .fetch_optional(&mut *tx)
    .await
    .map_err(db_err)?;
    let Some(row) = row else {
        return Err(ApiError::link_unknown());
    };
    let used_at: Option<DateTime<Utc>> = row.try_get("used_at").map_err(db_err)?;
    let expires_at: DateTime<Utc> = row.try_get("expires_at").map_err(db_err)?;
    if used_at.is_some() {
        return Err(ApiError::link_used());
    }
    if expires_at <= Utc::now() {
        return Err(ApiError::link_unknown());
    }
    let codes = unique_codes();
    sqlx::query("UPDATE admin.operators SET password_hash = $2 WHERE id = $1")
        .bind(link.operator_id)
        .bind(password_hash)
        .execute(&mut *tx)
        .await
        .map_err(db_err)?;
    sqlx::query("DELETE FROM admin.recovery_codes WHERE operator_id = $1")
        .bind(link.operator_id)
        .execute(&mut *tx)
        .await
        .map_err(db_err)?;
    for code in &codes {
        let hash = crypto::sha256(normalize_code(code).as_bytes());
        sqlx::query("INSERT INTO admin.recovery_codes (operator_id, code_hash) VALUES ($1, $2)")
            .bind(link.operator_id)
            .bind(hash.as_slice())
            .execute(&mut *tx)
            .await
            .map_err(db_err)?;
    }
    sqlx::query("UPDATE admin.setup_links SET used_at = now() WHERE token_hash = $1")
        .bind(&link.token_hash)
        .execute(&mut *tx)
        .await
        .map_err(db_err)?;
    write_audit_exec(&mut *tx, link.operator_id, "setup.complete", "ok", None).await?;
    tx.commit().await.map_err(db_err)?;
    Ok(codes)
}

fn unique_codes() -> Vec<String> {
    let mut codes = Vec::with_capacity(8);
    while codes.len() < 8 {
        let code = crypto::recovery_code();
        if !codes.contains(&code) {
            codes.push(code);
        }
    }
    codes
}

fn open_secret(key: &[u8; 32], blob: Option<&[u8]>) -> Option<Vec<u8>> {
    crypto::open(key, blob?).ok()
}

fn failure_detail(enabled: bool, password_ok: bool) -> Option<&'static str> {
    if !enabled {
        Some("Disabled")
    } else if !password_ok {
        Some("Wrong password")
    } else {
        Some("Wrong code")
    }
}

fn check_sign_in_fields(operator: &str, password: &str, extra: &str) -> Result<(), ApiError> {
    if operator.trim().is_empty() || password.is_empty() || extra.trim().is_empty() {
        return Err(ApiError::validation(
            "Enter the operator name, password and code.",
        ));
    }
    Ok(())
}

fn limit_key(name: &str) -> String {
    let trimmed = name.trim();
    if trimmed.is_empty() || trimmed.chars().count() > 64 {
        "#".to_owned()
    } else {
        trimmed.to_lowercase()
    }
}

async fn check_password(password: String, hash: Option<String>) -> Result<bool, ApiError> {
    let hash = hash.unwrap_or_else(|| password::dummy_hash().to_owned());
    tokio::task::spawn_blocking(move || password::verify(&password, &hash))
        .await
        .map_err(|_| ApiError::internal())?
        .map_err(|_| ApiError::internal())
}

async fn hash_password(password: String) -> Result<String, ApiError> {
    tokio::task::spawn_blocking(move || password::hash(&password))
        .await
        .map_err(|_| ApiError::internal())?
        .map_err(|_| ApiError::internal())
}

async fn fetch_operator(pool: &PgPool, name: &str) -> Result<Option<Operator>, ApiError> {
    let row = sqlx::query(
        "SELECT id, password_hash, totp_secret_enc, enabled FROM admin.operators WHERE name = $1",
    )
    .bind(name)
    .fetch_optional(pool)
    .await
    .map_err(db_err)?;
    row.map(|row| {
        Ok(Operator {
            id: row.try_get("id")?,
            password_hash: row.try_get("password_hash")?,
            totp_secret_enc: row.try_get("totp_secret_enc")?,
            enabled: row.try_get("enabled")?,
        })
    })
    .transpose()
    .map_err(db_err)
}

async fn matching_code(
    pool: &PgPool,
    operator_id: Uuid,
    recovery_code: &str,
) -> Result<Option<Vec<u8>>, ApiError> {
    let want = crypto::sha256(normalize_code(recovery_code).as_bytes());
    let rows: Vec<Vec<u8>> = sqlx::query_scalar(
        "SELECT code_hash FROM admin.recovery_codes WHERE operator_id = $1 AND used_at IS NULL",
    )
    .bind(operator_id)
    .fetch_all(pool)
    .await
    .map_err(db_err)?;
    let mut found: Option<Vec<u8>> = None;
    for hash in rows {
        let same = hash.len() == want.len() && bool::from(hash.as_slice().ct_eq(&want));
        if same && found.is_none() {
            found = Some(hash);
        }
    }
    Ok(found)
}

async fn burn_code(pool: &PgPool, operator_id: Uuid, code_hash: &[u8]) -> Result<(), ApiError> {
    let updated = sqlx::query(
        "UPDATE admin.recovery_codes SET used_at = now() WHERE operator_id = $1 AND code_hash = $2 AND used_at IS NULL",
    )
    .bind(operator_id)
    .bind(code_hash)
    .execute(pool)
    .await
    .map_err(db_err)?;
    if updated.rows_affected() == 1 {
        Ok(())
    } else {
        Err(ApiError::bad_credentials())
    }
}

async fn open_session(
    pool: &PgPool,
    operator_id: Uuid,
    action: &str,
) -> Result<(String, String), ApiError> {
    let session_id = crypto::random_hex(32);
    let csrf = crypto::random_hex(32);
    let id_hash = crypto::sha256(session_id.as_bytes());
    let mut tx = pool.begin().await.map_err(db_err)?;
    sqlx::query(
        "INSERT INTO admin.operator_sessions (id_hash, operator_id, csrf) VALUES ($1, $2, $3)",
    )
    .bind(id_hash.as_slice())
    .bind(operator_id)
    .bind(&csrf)
    .execute(&mut *tx)
    .await
    .map_err(db_err)?;
    sqlx::query("UPDATE admin.operators SET last_sign_in_at = now() WHERE id = $1")
        .bind(operator_id)
        .execute(&mut *tx)
        .await
        .map_err(db_err)?;
    write_audit_exec(&mut *tx, operator_id, action, "ok", None).await?;
    tx.commit().await.map_err(db_err)?;
    Ok((session_id, csrf))
}

async fn load_session(pool: &PgPool, raw_id: &str) -> Result<Option<LiveSession>, ApiError> {
    if raw_id.len() > 128 {
        return Ok(None);
    }
    let id_hash = crypto::sha256(raw_id.as_bytes());
    let row = sqlx::query(
        "SELECT s.operator_id, o.name, o.role, o.enabled, s.csrf, s.created_at, s.last_used_at, s.reauth_until
         FROM admin.operator_sessions s
         JOIN admin.operators o ON o.id = s.operator_id
         WHERE s.id_hash = $1",
    )
    .bind(id_hash.as_slice())
    .fetch_optional(pool)
    .await
    .map_err(db_err)?;
    let Some(row) = row else {
        return Ok(None);
    };
    let enabled: bool = row.try_get("enabled").map_err(db_err)?;
    if !enabled {
        return Ok(None);
    }
    Ok(Some(LiveSession {
        id_hash: id_hash.to_vec(),
        operator_id: row.try_get("operator_id").map_err(db_err)?,
        name: row.try_get("name").map_err(db_err)?,
        role: row.try_get("role").map_err(db_err)?,
        csrf: row.try_get("csrf").map_err(db_err)?,
        created_at: row.try_get("created_at").map_err(db_err)?,
        last_used_at: row.try_get("last_used_at").map_err(db_err)?,
        reauth_until: row.try_get("reauth_until").map_err(db_err)?,
    }))
}

async fn touch_session(pool: &PgPool, id_hash: &[u8]) -> Result<DateTime<Utc>, ApiError> {
    sqlx::query_scalar("UPDATE admin.operator_sessions SET last_used_at = now() WHERE id_hash = $1 RETURNING last_used_at")
        .bind(id_hash)
        .fetch_one(pool)
        .await
        .map_err(db_err)
}

async fn delete_session(pool: &PgPool, id_hash: &[u8]) -> Result<(), ApiError> {
    sqlx::query("DELETE FROM admin.operator_sessions WHERE id_hash = $1")
        .bind(id_hash)
        .execute(pool)
        .await
        .map_err(db_err)?;
    Ok(())
}

async fn write_audit(
    pool: &PgPool,
    operator_id: Uuid,
    action: &str,
    outcome: &str,
    detail: Option<&str>,
) -> Result<(), ApiError> {
    record_in(pool, operator_id, action, None, None, outcome, detail).await
}

async fn write_audit_exec<'e, E>(
    executor: E,
    operator_id: Uuid,
    action: &str,
    outcome: &str,
    detail: Option<&str>,
) -> Result<(), ApiError>
where
    E: sqlx::Executor<'e, Database = sqlx::Postgres>,
{
    record_in(executor, operator_id, action, None, None, outcome, detail).await
}

pub(crate) async fn record<'e, E>(
    executor: E,
    operator_id: Uuid,
    action: &str,
    target_kind: Option<&str>,
    target_id: Option<Uuid>,
    outcome: &str,
    detail: Option<&str>,
) -> Result<(), ApiError>
where
    E: sqlx::Executor<'e, Database = sqlx::Postgres>,
{
    record_in(
        executor,
        operator_id,
        action,
        target_kind,
        target_id,
        outcome,
        detail,
    )
    .await
}

async fn record_in<'e, E>(
    executor: E,
    operator_id: Uuid,
    action: &str,
    target_kind: Option<&str>,
    target_id: Option<Uuid>,
    outcome: &str,
    detail: Option<&str>,
) -> Result<(), ApiError>
where
    E: sqlx::Executor<'e, Database = sqlx::Postgres>,
{
    sqlx::query(
        "INSERT INTO admin.audit_log
         (operator_id, action, target_kind, target_id, outcome, detail)
         VALUES ($1, $2, $3, $4, $5, $6)",
    )
    .bind(operator_id)
    .bind(action)
    .bind(target_kind)
    .bind(target_id)
    .bind(outcome)
    .bind(detail)
    .execute(executor)
    .await
    .map_err(db_err)?;
    Ok(())
}

fn expires_at(created_at: DateTime<Utc>, last_used_at: DateTime<Utc>) -> DateTime<Utc> {
    let idle = last_used_at + TimeDelta::seconds(IDLE_SECS);
    let absolute = created_at + TimeDelta::seconds(ABSOLUTE_SECS);
    idle.min(absolute)
}

fn stamp(time: DateTime<Utc>) -> String {
    time.to_rfc3339_opts(SecondsFormat::Secs, true)
}

fn db_pool(state: &AppState) -> Result<PgPool, ApiError> {
    pool_result(state)
}

fn pool_result(state: &AppState) -> Result<PgPool, ApiError> {
    state
        .pool()
        .cloned()
        .ok_or_else(ApiError::upstream_postgres)
}

fn copy_key(state: &AppState) -> Result<[u8; 32], ApiError> {
    state.key().copied().ok_or_else(ApiError::internal)
}

fn db_err(err: sqlx::Error) -> ApiError {
    crate::db::log_db("admin", &err);
    ApiError::upstream_postgres()
}

fn cookie<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    let raw = headers.get(header::COOKIE)?.to_str().ok()?;
    raw.split(';').find_map(|part| {
        let (key, value) = part.trim().split_once('=')?;
        (key == name)
            .then_some(value.trim())
            .filter(|value| !value.is_empty())
    })
}

fn csrf_matches(headers: &HeaderMap, expected: &str) -> bool {
    let header = headers
        .get("x-admin-csrf")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("");
    let cookie = cookie(headers, CSRF_COOKIE).unwrap_or("");
    header.len() == expected.len()
        && cookie.len() == expected.len()
        && bool::from(header.as_bytes().ct_eq(expected.as_bytes()))
        && bool::from(cookie.as_bytes().ct_eq(expected.as_bytes()))
}

fn set_cookies(session: &str, csrf: &str) -> [String; 2] {
    [
        format!(
            "{SESSION_COOKIE}={session}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age={ABSOLUTE_SECS}"
        ),
        format!("{CSRF_COOKIE}={csrf}; Secure; SameSite=Strict; Path=/; Max-Age={ABSOLUTE_SECS}"),
    ]
}

fn clear_cookies() -> [String; 2] {
    [
        format!("{SESSION_COOKIE}=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0"),
        format!("{CSRF_COOKIE}=; Secure; SameSite=Strict; Path=/; Max-Age=0"),
    ]
}

fn no_content(cookies: &[String; 2]) -> Response {
    let mut response = StatusCode::NO_CONTENT.into_response();
    append_cookies(&mut response, cookies);
    response
}

fn unauthenticated_clear() -> Response {
    let mut response = ApiError::unauthenticated().into_response();
    append_cookies(&mut response, &clear_cookies());
    response
}

fn append_cookies(response: &mut Response, cookies: &[String; 2]) {
    for cookie in cookies {
        if let Ok(value) = HeaderValue::from_str(cookie) {
            response.headers_mut().append(header::SET_COOKIE, value);
        }
    }
}

type JsonRejection = axum::extract::rejection::JsonRejection;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_recover_and_name() {
        let opts = parse_args(std::iter::empty()).unwrap();
        assert!(!opts.recover);
        assert!(opts.name.is_none());
        let opts = parse_args(
            [
                "--recover".to_owned(),
                "--name".to_owned(),
                "ada".to_owned(),
            ]
            .into_iter(),
        )
        .unwrap();
        assert!(opts.recover);
        assert_eq!(opts.name.as_deref(), Some("ada"));
        assert!(parse_args(["--name".to_owned()].into_iter()).is_err());
    }

    #[test]
    fn public_url_drops_a_trailing_slash() {
        assert_eq!(
            clean_public_url("http://127.0.0.1:8082/").unwrap(),
            "http://127.0.0.1:8082"
        );
        assert!(clean_public_url("admin.example.com").is_err());
    }
}
