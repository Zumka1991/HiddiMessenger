use anyhow::Context;

/// Selects where opaque attachment blobs live. Metadata and access checks stay in SQLite.
/// S3 deliberately fails closed until its client, bucket policy, and integration tests exist.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AttachmentStorageBackend {
    Sqlite,
    S3,
}

impl AttachmentStorageBackend {
    pub fn from_environment() -> anyhow::Result<Self> {
        match std::env::var("HIDDI_ATTACHMENT_BACKEND")
            .unwrap_or_else(|_| "sqlite".into())
            .trim()
            .to_ascii_lowercase()
            .as_str()
        {
            "sqlite" => Ok(Self::Sqlite),
            "s3" => Ok(Self::S3),
            _ => anyhow::bail!("HIDDI_ATTACHMENT_BACKEND must be sqlite or s3"),
        }
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::Sqlite => "sqlite",
            Self::S3 => "s3",
        }
    }

    pub fn ensure_ready(self) -> anyhow::Result<()> {
        if self == Self::S3 {
            anyhow::bail!(
                "HIDDI_ATTACHMENT_BACKEND=s3 is reserved but not implemented; use sqlite"
            );
        }
        Ok(())
    }
}

#[allow(dead_code)]
fn _s3_environment_contract() -> anyhow::Result<()> {
    std::env::var("HIDDI_S3_ENDPOINT").context("HIDDI_S3_ENDPOINT must be set for S3")?;
    std::env::var("HIDDI_S3_BUCKET").context("HIDDI_S3_BUCKET must be set for S3")?;
    Ok(())
}
