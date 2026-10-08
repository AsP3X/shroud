//! Binary entry for the Shroud admin console.

use shroud_admin::run;

#[tokio::main]
async fn main() {
    let mut args = std::env::args().skip(1);
    match args.next().as_deref() {
        Some("bootstrap") => {
            if let Err(err) = shroud_admin::auth::bootstrap_cli(args).await {
                eprintln!("{err}");
                std::process::exit(err.exit_code());
            }
        }
        Some(other) => {
            eprintln!("unknown command {other}");
            eprintln!("Usage: shroud-admin [bootstrap [--recover] [--name NAME]]");
            std::process::exit(2);
        }
        None => {
            if let Err(err) = run().await {
                tracing::error!(error = %err, "admin console exited with error");
                std::process::exit(1);
            }
        }
    }
}
