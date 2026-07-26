# Hiddi Desktop

Compose Desktop client for Linux, Windows and macOS. The first packaged target is Linux (`.deb`).

## Security model

- A new account can be created with an invite; its recovery key is shown exactly once.
- A computer can be linked to an existing Hiddi account with a one-time, 10-minute code.
- Every computer creates its own Signal PQXDH identity and Kyber-1024 prekeys locally.
- Private key state is encrypted at rest with Argon2id and AES-256-GCM.
- Images and PCM voice messages are encrypted before upload; the local cache stores only
  ciphertext and attachment keys remain inside the Signal-encrypted chat history.
- Images are resized and re-encoded before encryption, so EXIF metadata is not sent.
- Voice is recorded directly into process memory and never touches a plaintext temporary file.
- Group names, membership changes and messages use the same Rust/OpenMLS core as Android.
  OpenMLS state is stored in an AES-256-SIV encrypted SQLite provider; only routing metadata
  reaches the server.
- The vault password is not sent to the server and is not stored on disk.
- Remote HTTP is rejected; production connections require HTTPS.

The desktop client supports realtime direct messages, images and voice messages shared with the
Android client. Attachment blobs on the server are opaque AES-GCM ciphertext.

## Run from sources

Java 21 is needed only to build from source:

```bash
cargo build --release --manifest-path group-mls-core/Cargo.toml
cd desktop-client
env JAVA_HOME=/opt/android-studio/jbr gradle run --no-daemon
```

On Android open `Настройки → Устройства → Привязать компьютер`, take a screenshot of the QR and
choose it in the desktop app (or paste the displayed code). The QR contains the same short-lived,
one-use code, so do not share its screenshot.

## Linux package

```bash
cd desktop-client
env JAVA_HOME=/opt/android-studio/jbr gradle packageDeb --no-daemon
```

The package is created under `build/compose/binaries/main/deb/` and includes its own Java runtime.

For CachyOS/Arch, where `dpkg` is normally absent, create an immediately runnable bundle instead:

```bash
cd desktop-client
env JAVA_HOME=/usr/lib/jvm/java-26-openjdk gradle -Dorg.gradle.java.installations.paths=/opt/android-studio/jbr createDistributable --no-daemon
build/compose/binaries/main/app/Hiddi/bin/Hiddi
```
