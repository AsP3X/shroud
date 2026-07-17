//! HTTP route tree for `/api/v1`.

use axum::{
    Router,
    extract::DefaultBodyLimit,
    routing::{delete, get, post, put},
};

use crate::state::AppState;

pub mod auth;
pub mod blocks;
pub mod calls;
pub mod contacts;
pub mod devices;
pub mod health;
pub mod keys;
pub mod media;
pub mod messages;
pub mod presence;
pub mod push;
pub mod users;
pub mod ws;

/// Builds the versioned API router mounted at `/api/v1`.
pub fn router() -> Router<AppState> {
    Router::new().nest(
        "/api/v1",
        Router::new()
            .route("/health", get(health::health))
            .route("/health/live", get(health::live))
            .route("/health/ready", get(health::ready))
            .route("/auth/register", post(auth::register))
            .route("/auth/login", post(auth::login))
            .route("/auth/logout", post(auth::logout))
            .route("/auth/me", get(auth::me))
            .route("/auth/password", post(auth::change_password))
            .route("/auth/account", delete(auth::delete_account))
            .route("/devices", get(devices::list_devices))
            .route("/devices/{id}", delete(devices::delete_device))
            .route("/keys/bundle", axum::routing::put(keys::put_bundle))
            .route("/keys/bundle/{user_id}", get(keys::get_bundle))
            .route("/keys/bundles/{user_id}", get(keys::get_bundles))
            .route("/keys/identity/{user_id}", get(keys::get_identity))
            .route("/keys/status", get(keys::keys_status))
            .route("/keys/otpk", post(keys::post_otpk))
            .route(
                "/users/by-username/{username}",
                get(users::get_user_by_username),
            )
            .route("/users/by-code/{code}", get(users::get_user_by_share_code))
            .route("/users/{user_id}", get(users::get_user))
            .route("/contacts/requests", post(contacts::create_request))
            .route("/contacts/requests", get(contacts::list_requests))
            .route(
                "/contacts/requests/{id}/accept",
                post(contacts::accept_request),
            )
            .route(
                "/contacts/requests/{id}/reject",
                post(contacts::reject_request),
            )
            .route(
                "/contacts/requests/{id}/cancel",
                post(contacts::cancel_request),
            )
            .route("/contacts", get(contacts::list_contacts))
            .route("/contacts/{user_id}", delete(contacts::delete_contact))
            .route("/blocks", post(blocks::create_block))
            .route("/blocks", get(blocks::list_blocks))
            .route("/blocks/{user_id}", delete(blocks::delete_block))
            .route("/messages", post(messages::send_message))
            .route("/messages", get(messages::list_messages))
            .route("/messages/read", post(messages::mark_read_bulk))
            .route("/messages/{id}/delivered", post(messages::mark_delivered))
            .route("/messages/{id}/read", post(messages::mark_read))
            .route("/messages/{id}", delete(messages::delete_message))
            .route("/conversations", get(messages::list_conversations))
            .route("/media/uploads", post(media::create_upload))
            .route("/media/{id}/download", post(media::create_download))
            // Axum’s default body limit is 2 MiB — raise to match MAX_MEDIA_BYTES so HD photos work.
            .route(
                "/media/{id}/content",
                axum::routing::put(media::put_content)
                    .get(media::get_content)
                    .layer(DefaultBodyLimit::max(media::MAX_MEDIA_BYTES as usize)),
            )
            .route("/presence/{user_id}", get(presence::get_presence))
            .route("/calls/ice-servers", get(calls::ice_servers))
            .route("/calls", post(calls::create_call))
            .route("/calls/{id}", get(calls::get_call))
            .route("/calls/{id}/accept", post(calls::accept_call))
            .route("/calls/{id}/reject", post(calls::reject_call))
            .route("/calls/{id}/hangup", post(calls::hangup_call))
            .route("/calls/{id}/signal", post(calls::signal_call))
            .route("/ws", get(ws::ws_upgrade))
            .route("/push/token", put(push::put_token)),
    )
}
