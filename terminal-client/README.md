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

На этом этапе сервер поддерживает одно устройство на ник; поддержка нескольких
устройств и encrypted groups будет следующим отдельным этапом.
