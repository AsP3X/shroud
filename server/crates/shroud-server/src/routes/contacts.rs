//! Contact requests, contacts list, and unfriend.

use axum::{
    Json,
    extract::{Path, Query, State},
    http::StatusCode,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct CreateRequestBody {
    pub user_id: Uuid,
}

#[derive(Debug, Deserialize)]
pub struct RequestsQuery {
    /// `incoming` (default) or `outgoing`.
    pub r#box: Option<String>,
    /// Default `pending`.
    pub status: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct ContactRequestResponse {
    pub id: Uuid,
    pub from_user_id: Uuid,
    pub to_user_id: Uuid,
    pub status: String,
    pub created_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub responded_at: Option<DateTime<Utc>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user: Option<PeerUser>,
}

#[derive(Debug, Serialize)]
pub struct PeerUser {
    pub id: Uuid,
    pub username: String,
}

#[derive(Debug, Serialize)]
pub struct RequestsListResponse {
    pub requests: Vec<ContactRequestResponse>,
}

#[derive(Debug, Serialize)]
pub struct ContactsListResponse {
    pub contacts: Vec<ContactItem>,
}

#[derive(Debug, Serialize)]
pub struct ContactItem {
    pub user_id: Uuid,
    pub username: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, FromRow)]
struct RequestRow {
    id: Uuid,
    from_user_id: Uuid,
    to_user_id: Uuid,
    status: String,
    created_at: DateTime<Utc>,
    responded_at: Option<DateTime<Utc>>,
}

/// `POST /contacts/requests`
pub async fn create_request(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<CreateRequestBody>,
) -> Result<(StatusCode, Json<ContactRequestResponse>), AppError> {
    if body.user_id == auth.user_id {
        return Err(AppError::validation(
            "Cannot send a contact request to yourself.",
        ));
    }

    let target_exists: bool =
        sqlx::query_scalar(r#"SELECT EXISTS(SELECT 1 FROM users WHERE id = $1)"#)
            .bind(body.user_id)
            .fetch_one(&state.pool)
            .await
            .map_err(|err| AppError::Internal(format!("target user check failed: {err}")))?;
    if !target_exists {
        return Err(AppError::not_found("User not found."));
    }

    if is_blocked_either_way(&state.pool, auth.user_id, body.user_id).await? {
        return Err(AppError::forbidden(
            "Cannot send a contact request while blocked.",
        ));
    }

    if are_contacts(&state.pool, auth.user_id, body.user_id).await? {
        return Err(AppError::already_exists("You are already contacts."));
    }

    if pending_exists(&state.pool, auth.user_id, body.user_id).await? {
        return Err(AppError::already_exists(
            "A pending contact request already exists.",
        ));
    }

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    // Mutual: reverse pending → accept both ways.
    let reverse: Option<Uuid> = sqlx::query_scalar(
        r#"
        SELECT id FROM contact_requests
        WHERE from_user_id = $1 AND to_user_id = $2 AND status = 'pending'
        FOR UPDATE
        "#,
    )
    .bind(body.user_id)
    .bind(auth.user_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("reverse pending lookup failed: {err}")))?;

    let now = Utc::now();

    if let Some(reverse_id) = reverse {
        sqlx::query(
            r#"
            UPDATE contact_requests
            SET status = 'accepted', responded_at = $1
            WHERE id = $2
            "#,
        )
        .bind(now)
        .bind(reverse_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("accept reverse request failed: {err}")))?;

        let request_id = Uuid::new_v4();
        sqlx::query(
            r#"
            INSERT INTO contact_requests (id, from_user_id, to_user_id, status, created_at, responded_at)
            VALUES ($1, $2, $3, 'accepted', $4, $4)
            "#,
        )
        .bind(request_id)
        .bind(auth.user_id)
        .bind(body.user_id)
        .bind(now)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("insert mutual request failed: {err}")))?;

        insert_contact_pair(&mut tx, auth.user_id, body.user_id, now).await?;

        tx.commit()
            .await
            .map_err(|err| AppError::Internal(format!("commit mutual accept failed: {err}")))?;

        return Ok((
            StatusCode::OK,
            Json(ContactRequestResponse {
                id: request_id,
                from_user_id: auth.user_id,
                to_user_id: body.user_id,
                status: "accepted".into(),
                created_at: now,
                responded_at: Some(now),
                user: None,
            }),
        ));
    }

    let request_id = Uuid::new_v4();
    let insert = sqlx::query(
        r#"
        INSERT INTO contact_requests (id, from_user_id, to_user_id, status, created_at)
        VALUES ($1, $2, $3, 'pending', $4)
        "#,
    )
    .bind(request_id)
    .bind(auth.user_id)
    .bind(body.user_id)
    .bind(now)
    .execute(&mut *tx)
    .await;

    match insert {
        Ok(_) => {}
        Err(sqlx::Error::Database(db))
            if db.constraint() == Some("contact_requests_one_pending") =>
        {
            return Err(AppError::already_exists(
                "A pending contact request already exists.",
            ));
        }
        Err(err) => {
            return Err(AppError::Internal(format!(
                "insert contact request failed: {err}"
            )));
        }
    }

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit contact request failed: {err}")))?;

    tracing::info!(
        request_id = %request_id,
        from_user_id = %auth.user_id,
        to_user_id = %body.user_id,
        "contacts.request_create ok"
    );

    Ok((
        StatusCode::CREATED,
        Json(ContactRequestResponse {
            id: request_id,
            from_user_id: auth.user_id,
            to_user_id: body.user_id,
            status: "pending".into(),
            created_at: now,
            responded_at: None,
            user: None,
        }),
    ))
}

/// `GET /contacts/requests`
pub async fn list_requests(
    State(state): State<AppState>,
    auth: AuthContext,
    Query(query): Query<RequestsQuery>,
) -> Result<Json<RequestsListResponse>, AppError> {
    let box_side = query.r#box.as_deref().unwrap_or("incoming");
    let status = query.status.as_deref().unwrap_or("pending");
    if !matches!(status, "pending" | "accepted" | "rejected" | "cancelled") {
        return Err(AppError::validation("Invalid status filter."));
    }

    let rows = match box_side {
        "incoming" => {
            sqlx::query_as::<_, RequestRow>(
                r#"
                SELECT id, from_user_id, to_user_id, status, created_at, responded_at
                FROM contact_requests
                WHERE to_user_id = $1 AND status = $2
                ORDER BY created_at DESC
                "#,
            )
            .bind(auth.user_id)
            .bind(status)
            .fetch_all(&state.pool)
            .await
        }
        "outgoing" => {
            sqlx::query_as::<_, RequestRow>(
                r#"
                SELECT id, from_user_id, to_user_id, status, created_at, responded_at
                FROM contact_requests
                WHERE from_user_id = $1 AND status = $2
                ORDER BY created_at DESC
                "#,
            )
            .bind(auth.user_id)
            .bind(status)
            .fetch_all(&state.pool)
            .await
        }
        _ => {
            return Err(AppError::validation(
                "box must be 'incoming' or 'outgoing'.",
            ));
        }
    }
    .map_err(|err| AppError::Internal(format!("list requests failed: {err}")))?;

    let mut requests = Vec::with_capacity(rows.len());
    for row in rows {
        let peer_id = if box_side == "incoming" {
            row.from_user_id
        } else {
            row.to_user_id
        };
        let username: String = sqlx::query_scalar(r#"SELECT username FROM users WHERE id = $1"#)
            .bind(peer_id)
            .fetch_one(&state.pool)
            .await
            .map_err(|err| AppError::Internal(format!("peer username failed: {err}")))?;

        requests.push(ContactRequestResponse {
            id: row.id,
            from_user_id: row.from_user_id,
            to_user_id: row.to_user_id,
            status: row.status,
            created_at: row.created_at,
            responded_at: row.responded_at,
            user: Some(PeerUser {
                id: peer_id,
                username,
            }),
        });
    }

    Ok(Json(RequestsListResponse { requests }))
}

/// `POST /contacts/requests/:id/accept`
pub async fn accept_request(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(request_id): Path<Uuid>,
) -> Result<Json<ContactRequestResponse>, AppError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let row = sqlx::query_as::<_, RequestRow>(
        r#"
        SELECT id, from_user_id, to_user_id, status, created_at, responded_at
        FROM contact_requests
        WHERE id = $1
        FOR UPDATE
        "#,
    )
    .bind(request_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("load request failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Contact request not found."))?;

    if row.to_user_id != auth.user_id {
        return Err(AppError::forbidden(
            "Only the recipient can accept this request.",
        ));
    }
    if row.status != "pending" {
        return Err(AppError::validation("Contact request is not pending."));
    }

    if is_blocked_either_way_tx(&mut tx, row.from_user_id, row.to_user_id).await? {
        return Err(AppError::forbidden(
            "Cannot accept a contact request while blocked.",
        ));
    }

    let now = Utc::now();
    sqlx::query(
        r#"
        UPDATE contact_requests
        SET status = 'accepted', responded_at = $1
        WHERE id = $2
        "#,
    )
    .bind(now)
    .bind(request_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("accept request failed: {err}")))?;

    insert_contact_pair(&mut tx, row.from_user_id, row.to_user_id, now).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit accept failed: {err}")))?;

    tracing::info!(
        request_id = %row.id,
        from_user_id = %row.from_user_id,
        to_user_id = %row.to_user_id,
        accepter_user_id = %auth.user_id,
        "contacts.request_accept ok"
    );

    Ok(Json(ContactRequestResponse {
        id: row.id,
        from_user_id: row.from_user_id,
        to_user_id: row.to_user_id,
        status: "accepted".into(),
        created_at: row.created_at,
        responded_at: Some(now),
        user: None,
    }))
}

/// `POST /contacts/requests/:id/reject`
pub async fn reject_request(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(request_id): Path<Uuid>,
) -> Result<Json<ContactRequestResponse>, AppError> {
    respond_as_recipient(&state, auth.user_id, request_id, "rejected").await
}

/// `POST /contacts/requests/:id/cancel`
pub async fn cancel_request(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(request_id): Path<Uuid>,
) -> Result<Json<ContactRequestResponse>, AppError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let row = sqlx::query_as::<_, RequestRow>(
        r#"
        SELECT id, from_user_id, to_user_id, status, created_at, responded_at
        FROM contact_requests
        WHERE id = $1
        FOR UPDATE
        "#,
    )
    .bind(request_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("load request failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Contact request not found."))?;

    if row.from_user_id != auth.user_id {
        return Err(AppError::forbidden(
            "Only the sender can cancel this request.",
        ));
    }
    if row.status != "pending" {
        return Err(AppError::validation("Contact request is not pending."));
    }

    let now = Utc::now();
    sqlx::query(
        r#"
        UPDATE contact_requests
        SET status = 'cancelled', responded_at = $1
        WHERE id = $2
        "#,
    )
    .bind(now)
    .bind(request_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("cancel request failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit cancel failed: {err}")))?;

    Ok(Json(ContactRequestResponse {
        id: row.id,
        from_user_id: row.from_user_id,
        to_user_id: row.to_user_id,
        status: "cancelled".into(),
        created_at: row.created_at,
        responded_at: Some(now),
        user: None,
    }))
}

/// `GET /contacts`
pub async fn list_contacts(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<Json<ContactsListResponse>, AppError> {
    #[derive(FromRow)]
    struct Row {
        contact_user_id: Uuid,
        username: String,
        created_at: DateTime<Utc>,
    }

    let rows = sqlx::query_as::<_, Row>(
        r#"
        SELECT c.contact_user_id, u.username, c.created_at
        FROM contacts c
        INNER JOIN users u ON u.id = c.contact_user_id
        WHERE c.user_id = $1
        ORDER BY u.username ASC
        "#,
    )
    .bind(auth.user_id)
    .fetch_all(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("list contacts failed: {err}")))?;

    Ok(Json(ContactsListResponse {
        contacts: rows
            .into_iter()
            .map(|row| ContactItem {
                user_id: row.contact_user_id,
                username: row.username,
                created_at: row.created_at,
            })
            .collect(),
    }))
}

/// `DELETE /contacts/:user_id` — unfriend both directions.
pub async fn delete_contact(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(peer_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let result = sqlx::query(
        r#"
        DELETE FROM contacts
        WHERE (user_id = $1 AND contact_user_id = $2)
           OR (user_id = $2 AND contact_user_id = $1)
        "#,
    )
    .bind(auth.user_id)
    .bind(peer_id)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("delete contact failed: {err}")))?;

    if result.rows_affected() == 0 {
        return Err(AppError::not_found("Contact not found."));
    }

    Ok(StatusCode::NO_CONTENT)
}

async fn respond_as_recipient(
    state: &AppState,
    user_id: Uuid,
    request_id: Uuid,
    status: &str,
) -> Result<Json<ContactRequestResponse>, AppError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let row = sqlx::query_as::<_, RequestRow>(
        r#"
        SELECT id, from_user_id, to_user_id, status, created_at, responded_at
        FROM contact_requests
        WHERE id = $1
        FOR UPDATE
        "#,
    )
    .bind(request_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("load request failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Contact request not found."))?;

    if row.to_user_id != user_id {
        return Err(AppError::forbidden(
            "Only the recipient can respond to this request.",
        ));
    }
    if row.status != "pending" {
        return Err(AppError::validation("Contact request is not pending."));
    }

    let now = Utc::now();
    sqlx::query(
        r#"
        UPDATE contact_requests
        SET status = $1, responded_at = $2
        WHERE id = $3
        "#,
    )
    .bind(status)
    .bind(now)
    .bind(request_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("update request failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit respond failed: {err}")))?;

    Ok(Json(ContactRequestResponse {
        id: row.id,
        from_user_id: row.from_user_id,
        to_user_id: row.to_user_id,
        status: status.into(),
        created_at: row.created_at,
        responded_at: Some(now),
        user: None,
    }))
}

async fn insert_contact_pair(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    a: Uuid,
    b: Uuid,
    now: DateTime<Utc>,
) -> Result<(), AppError> {
    for (user_id, contact_user_id) in [(a, b), (b, a)] {
        sqlx::query(
            r#"
            INSERT INTO contacts (user_id, contact_user_id, created_at)
            VALUES ($1, $2, $3)
            ON CONFLICT DO NOTHING
            "#,
        )
        .bind(user_id)
        .bind(contact_user_id)
        .bind(now)
        .execute(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("insert contact failed: {err}")))?;
    }
    Ok(())
}

async fn are_contacts(pool: &sqlx::PgPool, a: Uuid, b: Uuid) -> Result<bool, AppError> {
    sqlx::query_scalar(
        r#"
        SELECT EXISTS(
            SELECT 1 FROM contacts WHERE user_id = $1 AND contact_user_id = $2
        )
        "#,
    )
    .bind(a)
    .bind(b)
    .fetch_one(pool)
    .await
    .map_err(|err| AppError::Internal(format!("contacts check failed: {err}")))
}

async fn pending_exists(pool: &sqlx::PgPool, from: Uuid, to: Uuid) -> Result<bool, AppError> {
    sqlx::query_scalar(
        r#"
        SELECT EXISTS(
            SELECT 1 FROM contact_requests
            WHERE from_user_id = $1 AND to_user_id = $2 AND status = 'pending'
        )
        "#,
    )
    .bind(from)
    .bind(to)
    .fetch_one(pool)
    .await
    .map_err(|err| AppError::Internal(format!("pending check failed: {err}")))
}

pub(crate) async fn is_blocked_either_way(
    pool: &sqlx::PgPool,
    a: Uuid,
    b: Uuid,
) -> Result<bool, AppError> {
    sqlx::query_scalar(
        r#"
        SELECT EXISTS(
            SELECT 1 FROM blocks
            WHERE (blocker_id = $1 AND blocked_id = $2)
               OR (blocker_id = $2 AND blocked_id = $1)
        )
        "#,
    )
    .bind(a)
    .bind(b)
    .fetch_one(pool)
    .await
    .map_err(|err| AppError::Internal(format!("block check failed: {err}")))
}

async fn is_blocked_either_way_tx(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    a: Uuid,
    b: Uuid,
) -> Result<bool, AppError> {
    sqlx::query_scalar(
        r#"
        SELECT EXISTS(
            SELECT 1 FROM blocks
            WHERE (blocker_id = $1 AND blocked_id = $2)
               OR (blocker_id = $2 AND blocked_id = $1)
        )
        "#,
    )
    .bind(a)
    .bind(b)
    .fetch_one(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("block check failed: {err}")))
}
