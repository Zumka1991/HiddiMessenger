use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use reqwest::blocking::{Client, RequestBuilder};
use serde_json::{Value, json};

#[derive(Parser)]
#[command(
    name = "hiddi-cli",
    about = "Тестовый транспортный клиент Hiddi Messenger"
)]
struct Cli {
    #[arg(long, default_value = "http://127.0.0.1:3000")]
    server: String,
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    Invite {
        #[arg(long)]
        admin_secret: String,
    },
    Register {
        #[arg(long)]
        nickname: String,
        #[arg(long)]
        invite: String,
        #[arg(long)]
        public_key: String,
        /// Signal registration ID (1..=16380). This test client does not create Signal keys.
        #[arg(long)]
        registration_id: u32,
    },
    Find {
        #[arg(long)]
        token: String,
        nickname: String,
    },
    Send {
        #[arg(long)]
        token: String,
        #[arg(long)]
        to: String,
        #[arg(long)]
        ciphertext: String,
    },
    Inbox {
        #[arg(long)]
        token: String,
    },
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    let client = Client::new();
    let base = cli.server.trim_end_matches('/');
    let response = match cli.command {
        Command::Invite { admin_secret } => client.post(format!("{base}/v1/admin/invites"))
            .bearer_auth(admin_secret).send(),
        Command::Register { nickname, invite, public_key, registration_id } => client.post(format!("{base}/v1/auth/register"))
            .json(&json!({"nickname": nickname, "invite_code": invite, "identity_public_key": public_key, "registration_id": registration_id})).send(),
        Command::Find { token, nickname } => authorized(client.get(format!("{base}/v1/users/{nickname}")), token).send(),
        Command::Send { token, to, ciphertext } => authorized(client.post(format!("{base}/v1/messages")), token)
            .json(&json!({"recipient_nickname": to, "ciphertext": ciphertext})).send(),
        Command::Inbox { token } => authorized(client.get(format!("{base}/v1/messages")), token).send(),
    }.context("could not connect to server")?;
    let status = response.status();
    let body: Value = response.json().context("server did not return JSON")?;
    println!("{}", serde_json::to_string_pretty(&body)?);
    if !status.is_success() {
        anyhow::bail!("server returned HTTP {status}");
    }
    Ok(())
}

fn authorized(request: RequestBuilder, token: String) -> RequestBuilder {
    request.bearer_auth(token)
}
