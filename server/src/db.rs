use rusqlite::Connection;

pub(crate) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch(
        "PRAGMA foreign_keys = ON;
         CREATE TABLE IF NOT EXISTS invites (
            code_hash TEXT PRIMARY KEY NOT NULL,
            used_at TEXT
         );
         CREATE TABLE IF NOT EXISTS accounts (
            id TEXT PRIMARY KEY NOT NULL,
            nickname TEXT UNIQUE NOT NULL COLLATE NOCASE,
            display_name TEXT NOT NULL DEFAULT '',
            bio TEXT NOT NULL DEFAULT '',
            avatar BLOB,
            avatar_version TEXT,
            recovery_public_key TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY NOT NULL,
            account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            identity_public_key TEXT NOT NULL,
            access_token_hash TEXT NOT NULL UNIQUE,
            registration_id INTEGER NOT NULL DEFAULT 0,
            device_number INTEGER NOT NULL DEFAULT 1,
            device_name TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS device_link_sessions (
            id TEXT PRIMARY KEY NOT NULL,
            account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            authorized_by_device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            code_hash TEXT NOT NULL UNIQUE,
            expires_at INTEGER NOT NULL,
            consumed_at TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS recovery_challenges (
            id TEXT PRIMARY KEY NOT NULL,
            account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            challenge TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            consumed_at TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS blocks (
            blocker_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            blocked_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY(blocker_account_id, blocked_account_id),
            CHECK(blocker_account_id != blocked_account_id)
         );
         CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY NOT NULL,
            sender_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            sender_device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            ciphertext BLOB NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS groups (
            id TEXT PRIMARY KEY NOT NULL,
            owner_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS group_members (
            group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            role TEXT NOT NULL CHECK(role IN ('owner', 'admin', 'member')),
            PRIMARY KEY(group_id, account_id)
         );
         CREATE TABLE IF NOT EXISTS group_events (
            id TEXT PRIMARY KEY NOT NULL,
            group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            sender_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            client_event_id TEXT,
            kind INTEGER NOT NULL CHECK(kind IN (1, 2, 3)),
            envelope TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            delivered_at TEXT,
            removes_recipient INTEGER NOT NULL DEFAULT 0
         );
         CREATE TABLE IF NOT EXISTS mls_key_packages (
            id TEXT PRIMARY KEY NOT NULL,
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            key_package TEXT UNIQUE NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS attachments (
            id TEXT PRIMARY KEY NOT NULL,
            sender_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            ciphertext TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            delivered_at TEXT
         );
         CREATE TABLE IF NOT EXISTS group_attachments (
            id TEXT PRIMARY KEY NOT NULL,
            group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            sender_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            ciphertext TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS conversation_deletions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            peer_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS message_deletions (
            id TEXT PRIMARY KEY NOT NULL,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            message_id TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(recipient_account_id, message_id)
         );
         CREATE TABLE IF NOT EXISTS group_deletions (
            id TEXT PRIMARY KEY NOT NULL,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            group_id TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(recipient_account_id, group_id)
         );
         CREATE TABLE IF NOT EXISTS prekey_bundles (
            device_id TEXT PRIMARY KEY NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            signed_prekey_id INTEGER NOT NULL,
            signed_prekey TEXT NOT NULL,
            signed_prekey_signature TEXT NOT NULL,
            kyber_signed_prekey_id INTEGER NOT NULL,
            kyber_signed_prekey TEXT NOT NULL,
            kyber_signed_prekey_signature TEXT NOT NULL
         );
         CREATE TABLE IF NOT EXISTS one_time_prekeys (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            key_kind TEXT NOT NULL CHECK(key_kind IN ('classical', 'kyber')),
            key_id INTEGER NOT NULL,
            public_key TEXT NOT NULL,
            signature TEXT,
            UNIQUE(device_id, key_kind, key_id)
         );",
    )?;
    let mls_key_package_columns = db
        .prepare("PRAGMA table_info(mls_key_packages)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    if !mls_key_package_columns.iter().any(|column| column == "id") {
        db.execute_batch(
            "ALTER TABLE mls_key_packages RENAME TO mls_key_packages_legacy;
             CREATE TABLE mls_key_packages (
                id TEXT PRIMARY KEY NOT NULL,
                device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                key_package TEXT UNIQUE NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
             );
             INSERT INTO mls_key_packages (id, device_id, key_package, created_at)
             SELECT lower(hex(randomblob(16))), device_id, key_package, created_at
             FROM mls_key_packages_legacy;
             DROP TABLE mls_key_packages_legacy;",
        )?;
    }
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_mls_key_packages_device
         ON mls_key_packages(device_id, created_at)",
        [],
    )?;
    let has_registration_id = db
        .prepare("PRAGMA table_info(devices)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "registration_id");
    if !has_registration_id {
        db.execute(
            "ALTER TABLE devices ADD COLUMN registration_id INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
    }
    let device_columns = db
        .prepare("PRAGMA table_info(devices)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    if !device_columns
        .iter()
        .any(|column| column == "device_number")
    {
        db.execute(
            "ALTER TABLE devices ADD COLUMN device_number INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
        db.execute(
            "UPDATE devices
             SET device_number = (
               SELECT COUNT(*) FROM devices AS earlier
               WHERE earlier.account_id = devices.account_id
                 AND (earlier.created_at < devices.created_at
                      OR (earlier.created_at = devices.created_at AND earlier.id <= devices.id))
             )",
            [],
        )?;
    }
    if !device_columns.iter().any(|column| column == "device_name") {
        db.execute(
            "ALTER TABLE devices ADD COLUMN device_name TEXT NOT NULL DEFAULT ''",
            [],
        )?;
    }
    db.execute(
        "UPDATE devices SET device_name = CASE
           WHEN device_number = 1 THEN 'Android'
           ELSE 'Device ' || device_number
         END WHERE device_name = ''",
        [],
    )?;
    db.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_account_number
         ON devices(account_id, device_number)",
        [],
    )?;
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_device_link_sessions_expiry
         ON device_link_sessions(account_id, expires_at)",
        [],
    )?;
    let has_delivered_at = db
        .prepare("PRAGMA table_info(messages)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "delivered_at");
    if !has_delivered_at {
        db.execute("ALTER TABLE messages ADD COLUMN delivered_at TEXT", [])?;
    }
    let has_read_at = db
        .prepare("PRAGMA table_info(messages)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "read_at");
    if !has_read_at {
        db.execute("ALTER TABLE messages ADD COLUMN read_at TEXT", [])?;
    }
    let message_columns = db
        .prepare("PRAGMA table_info(messages)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    if !message_columns
        .iter()
        .any(|column| column == "sender_device_id")
    {
        db.execute(
            "ALTER TABLE messages ADD COLUMN sender_device_id TEXT REFERENCES devices(id) ON DELETE SET NULL",
            [],
        )?;
    }
    let has_group_client_event_id = db
        .prepare("PRAGMA table_info(group_events)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "client_event_id");
    if !has_group_client_event_id {
        db.execute(
            "ALTER TABLE group_events ADD COLUMN client_event_id TEXT",
            [],
        )?;
    }
    let has_group_removes_recipient = db
        .prepare("PRAGMA table_info(group_events)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "removes_recipient");
    if !has_group_removes_recipient {
        db.execute(
            "ALTER TABLE group_events
             ADD COLUMN removes_recipient INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
    }
    let account_columns = db
        .prepare("PRAGMA table_info(accounts)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    if !account_columns
        .iter()
        .any(|column| column == "display_name")
    {
        db.execute(
            "ALTER TABLE accounts ADD COLUMN display_name TEXT NOT NULL DEFAULT ''",
            [],
        )?;
    }
    if !account_columns
        .iter()
        .any(|column| column == "recovery_public_key")
    {
        db.execute(
            "ALTER TABLE accounts ADD COLUMN recovery_public_key TEXT",
            [],
        )?;
    }
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_recovery_challenges_expiry
         ON recovery_challenges(account_id, expires_at)",
        [],
    )?;
    if !account_columns.iter().any(|column| column == "bio") {
        db.execute(
            "ALTER TABLE accounts ADD COLUMN bio TEXT NOT NULL DEFAULT ''",
            [],
        )?;
    }
    if !account_columns.iter().any(|column| column == "avatar") {
        db.execute("ALTER TABLE accounts ADD COLUMN avatar BLOB", [])?;
    }
    if !account_columns
        .iter()
        .any(|column| column == "avatar_version")
    {
        db.execute("ALTER TABLE accounts ADD COLUMN avatar_version TEXT", [])?;
    }
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_messages_pending
         ON messages(recipient_account_id, delivered_at, created_at)",
        [],
    )?;
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_group_events_pending
         ON group_events(recipient_account_id, delivered_at, created_at)",
        [],
    )?;
    db.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_group_events_idempotency
         ON group_events(sender_account_id, recipient_account_id, client_event_id)
         WHERE client_event_id IS NOT NULL",
        [],
    )?;
    db.execute(
        "UPDATE devices SET registration_id = (ABS(RANDOM()) % 16380) + 1 WHERE registration_id = 0",
        [],
    )?;
    Ok(())
}
