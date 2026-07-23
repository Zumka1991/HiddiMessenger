# Hiddi Terminal

Terminal-клиент использует тот же официальный `libsignal`, что и Android, а не
собственную реализацию криптографии. Приватные ключи, токен и Signal-records
хранятся только локально: парольная фраза выводит ключ через Argon2id, а файл
защищён AES-256-GCM.

Для сборки требуется Java 21. На установленной Android Studio можно выполнить:

```fish
cd terminal-client
env JAVA_HOME=/opt/android-studio/jbr gradle run --args=help --no-daemon
```

Регистрация terminal-устройства (в интерактивном терминале):

```fish
env JAVA_HOME=/opt/android-studio/jbr gradle run --args='register --server https://messenger.example --nickname alice --invite INVITE_CODE' --no-daemon
```

Для локального Docker-теста разрешён адрес `http://127.0.0.1:3000`. Команды
`chat` и `inbox` используют Signal-сессию и сохраняют её ratchet-state в том же
зашифрованном vault:

```fish
env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal chat \
  --to nickname --message 'Привет'

env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal inbox
```

MLS-группы используют тот же Rust/OpenMLS core, что и Android. Сначала соберите
нативную библиотеку и terminal distribution:

```fish
cargo build --manifest-path ../group-mls-core/Cargo.toml --release
env JAVA_HOME=/opt/android-studio/jbr gradle installDist --no-daemon
```

Затем опубликуйте одноразовый KeyPackage и используйте групповые команды:

```fish
env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal group publish
env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal group create --with nickname
env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal group invite --group GROUP_PREFIX --with another_nickname
env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal group list
env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal group send --message 'Привет группе'
env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal group delete-message --group GROUP_PREFIX
env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal group inbox
env JAVA_HOME=/opt/android-studio/jbr ./build/install/hiddi-terminal/bin/hiddi-terminal group delete --group GROUP_PREFIX
```

64-байтный ключ шифрования OpenMLS SQLite хранится только внутри существующего
Argon2id/AES-GCM vault. Владелец уже может приглашать третьего и последующих
участников через OpenMLS Add Commit/Welcome и удалять группу. Удаление
участников с обязательной ротацией epoch и multi-device остаются следующим
этапом.
