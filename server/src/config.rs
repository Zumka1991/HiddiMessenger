use std::{env, fs, path::PathBuf};

/// Environment-derived startup settings. Keeping parsing here makes `main` a
/// small composition root and gives configuration one well-defined home.
pub(crate) struct ServerConfig {
    pub(crate) bootstrap_secret: String,
    pub(crate) database_path: PathBuf,
}

impl ServerConfig {
    pub(crate) fn from_environment() -> anyhow::Result<Self> {
        let bootstrap_secret = match env::var("HIDDI_BOOTSTRAP_SECRET_FILE") {
            Ok(path) => fs::read_to_string(&path)
                .map_err(anyhow::Error::from)
                .map_err(|error| {
                    error.context(format!(
                        "could not read HIDDI_BOOTSTRAP_SECRET_FILE at {path}"
                    ))
                })
                .map(|value| value.trim().to_owned())?,
            Err(_) => env::var("HIDDI_BOOTSTRAP_SECRET").map_err(|_| {
                anyhow::anyhow!("HIDDI_BOOTSTRAP_SECRET or HIDDI_BOOTSTRAP_SECRET_FILE must be set")
            })?,
        };
        if bootstrap_secret.len() < 32 {
            anyhow::bail!("HIDDI_BOOTSTRAP_SECRET must be at least 32 characters");
        }
        Ok(Self {
            bootstrap_secret,
            database_path: env::var("HIDDI_DATABASE_PATH")
                .unwrap_or_else(|_| "../data/hiddi.db".into())
                .into(),
        })
    }

    pub(crate) fn log_filter() -> String {
        env::var("RUST_LOG").unwrap_or_else(|_| "hiddi_server=info,tower_http=info".into())
    }
}
