# Hiddi CLI

Это тестовый клиент транспорта, а не защищённый мессенджер. Он не генерирует
Signal-ключи и не шифрует текст: параметр `--ciphertext` должен уже содержать
URL-safe Base64 шифротекст. Не используйте его для реальных приватных данных.

Пример после запуска сервера:

```bash
# Создать инвайт; секрет берётся из .env.
cargo run -- invite --admin-secret "$HIDDI_BOOTSTRAP_SECRET"

# Зарегистрировать устройство. Сохраните access_token из ответа временно.
cargo run -- register --nickname alice --invite '<invite>' --public-key 'test-public-key-material-long-enough'

# Найти пользователя и отправить тестовый шифротекст `hello` в Base64url.
cargo run -- find --token '<token>' alice
cargo run -- send --token '<token>' --to bob --ciphertext aGVsbG8
cargo run -- inbox --token '<token>'
```
