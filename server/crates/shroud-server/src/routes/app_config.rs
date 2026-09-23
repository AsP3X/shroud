//! Settings the server hands to its clients: behaviour an operator can change without an app
//! update.
//!
//! Human: Clients fetch this after sign-in and when they come back to the foreground, and keep
//! the last answer for offline starts. Anything here must be safe for every signed-in user to
//! read.
//! Agent: READS AppState only; no database access.

use axum::{Json, extract::State};
use serde::Serialize;

use crate::auth::session::AuthContext;
use crate::state::AppState;

#[derive(Debug, Serialize)]
pub struct ClientConfig {
    pub reactions: ReactionsConfig,
}

#[derive(Debug, Serialize)]
pub struct ReactionsConfig {
    /// Most emoji one person may leave on one message (`REACTIONS_MAX_PER_USER`). The server
    /// can't count them — they are sealed — so clients enforce it when adding; readers show
    /// whatever a record holds, within their own safety cap.
    pub max_per_user: u32,
}

/// `GET /config`
pub async fn get_config(State(state): State<AppState>, _auth: AuthContext) -> Json<ClientConfig> {
    Json(ClientConfig {
        reactions: ReactionsConfig {
            max_per_user: state.reactions_max_per_user,
        },
    })
}
