//! Binary entry for the Shroud API process.

use shroud_server::run;

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        tracing::error!(error = %err, "server exited with error");
        std::process::exit(1);
    }
}
