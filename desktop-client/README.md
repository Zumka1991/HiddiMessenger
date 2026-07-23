# Hiddi Desktop

Compose Desktop client for Linux, Windows and macOS. The first packaged target is Linux (`.deb`).

## Security model

- A computer is linked to an existing Hiddi account with a one-time, 10-minute code.
- Every computer creates its own Signal PQXDH identity and Kyber-1024 prekeys locally.
- Private key state is encrypted at rest with Argon2id and AES-256-GCM.
- The vault password is not sent to the server and is not stored on disk.
- Remote HTTP is rejected; production connections require HTTPS.

The initial Linux milestone can securely send a direct message to a recipient's primary device.
Receiving on every linked device requires per-device server fan-out and is intentionally not
emulated with the legacy account-wide inbox.

## Run from sources

Java 21 is needed only to build from source:

```bash
cd desktop-client
env JAVA_HOME=/opt/android-studio/jbr gradle run --no-daemon
```

On Android open `Настройки → Устройства → Привязать компьютер`, copy the code, and paste it into
the desktop app.

## Linux package

```bash
cd desktop-client
env JAVA_HOME=/opt/android-studio/jbr gradle packageDeb --no-daemon
```

The package is created under `build/compose/binaries/main/deb/` and includes its own Java runtime.
