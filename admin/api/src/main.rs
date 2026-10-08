//! Binary entry for the Shroud admin console.

use shroud_admin::run;

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        tracing::error!(error = %err, "admin console exited with error");
        std::process::exit(1);
    }
}
