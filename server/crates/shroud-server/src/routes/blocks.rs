//! Block and unblock users.

use axum::{
    Json,
    extract::{Path, State},
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
pub struct BlockBody {
    pub user_id: Uuid,
}

#[derive(Debug, Serialize)]
pub struct BlocksListResponse {
    pub blocks: Vec<BlockItem>,
}

#[derive(Debug, Serialize)]
pub struct BlockItem {
    pub user_id: Uuid,
    pub created_at: DateTime<Utc>,
}

/// `POST /blocks`
pub async fn create_block(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<BlockBody>,
) -> Result<StatusCode, AppError> {
    if body.user_id == auth.user_id {
        return Err(AppError::validation("Cannot block yourself."));
    }

    // A deleted account has no username for `GET /blocks` to list, and nothing left to block.
    let exists: bool = sqlx::query_scalar(
        r#"SELECT EXISTS(SELECT 1 FROM users WHERE id = $1 AND deleted_at IS NULL)"#,
    )
    .bind(body.user_id)
    .fetch_one(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("block target check failed: {err}")))?;
    if !exists {
        return Err(AppError::not_found("User not found."));
    }

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let now = Utc::now();

    // Drop contact edges both ways.
    sqlx::query(
        r#"
        DELETE FROM contacts
        WHERE (user_id = $1 AND contact_user_id = $2)
           OR (user_id = $2 AND contact_user_id = $1)
        "#,
    )
    .bind(auth.user_id)
    .bind(body.user_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("delete contacts on block failed: {err}")))?;

    // Cancel pending requests either way.
    sqlx::query(
        r#"
        UPDATE contact_requests
        SET status = 'cancelled', responded_at = $1
        WHERE status = 'pending'
          AND (
            (from_user_id = $2 AND to_user_id = $3)
            OR (from_user_id = $3 AND to_user_id = $2)
          )
        "#,
    )
    .bind(now)
    .bind(auth.user_id)
    .bind(body.user_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("cancel requests on block failed: {err}")))?;

    sqlx::query(
        r#"
        INSERT INTO blocks (blocker_id, blocked_id, created_at)
        VALUES ($1, $2, $3)
        ON CONFLICT DO NOTHING
        "#,
    )
    .bind(auth.user_id)
    .bind(body.user_id)
    .bind(now)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("insert block failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit block failed: {err}")))?;

    Ok(StatusCode::NO_CONTENT)
}

/// `DELETE /blocks/:user_id`
pub async fn delete_block(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(user_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let result = sqlx::query(
        r#"
        DELETE FROM blocks
        WHERE blocker_id = $1 AND blocked_id = $2
        "#,
    )
    .bind(auth.user_id)
    .bind(user_id)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("delete block failed: {err}")))?;

    if result.rows_affected() == 0 {
        return Err(AppError::not_found("Block not found."));
    }

    Ok(StatusCode::NO_CONTENT)
}

/// `GET /blocks`
pub async fn list_blocks(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<Json<BlocksListResponse>, AppError> {
    #[derive(FromRow)]
    struct Row {
        blocked_id: Uuid,
        created_at: DateTime<Utc>,
    }

    let rows = sqlx::query_as::<_, Row>(
        r#"
        SELECT b.blocked_id, b.created_at
        FROM blocks b
        INNER JOIN users u ON u.id = b.blocked_id AND u.deleted_at IS NULL
        WHERE b.blocker_id = $1
        ORDER BY b.created_at DESC
        "#,
    )
    .bind(auth.user_id)
    .fetch_all(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("list blocks failed: {err}")))?;

    Ok(Json(BlocksListResponse {
        blocks: rows
            .into_iter()
            .map(|row| BlockItem {
                user_id: row.blocked_id,
                created_at: row.created_at,
            })
            .collect(),
    }))
}
