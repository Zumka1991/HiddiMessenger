# Hiddi Messenger

Закрытый мессенджер с регистрацией по приглашениям и сквозным шифрованием.

В репозитории есть Rust-сервер, Android-клиент и terminal-клиент. Личные диалоги
используют Signal Protocol/PQXDH, а первый двухпользовательский групповой контур
использует общий Rust/OpenMLS core. Сервер хранит и маршрутизирует только
шифротексты; приватные ключи остаются на клиентских устройствах.

## Запуск сервера в Docker

```bash
cp .env.example .env
# Отредактируйте .env: задайте уникальный секрет длиной не менее 32 символов.
docker compose up --build -d
curl http://127.0.0.1:3000/health
```

База данных хранится в Docker volume `hiddi_data`. Порт намеренно опубликован
только на `127.0.0.1`: внешний HTTPS должен обеспечивать reverse-proxy (Caddy,
Nginx или Traefik). Не публикуйте API в интернет напрямую без TLS.

## Локальный запуск без Docker

Нужен установленный Rust. Затем:

```bash
cd server
export HIDDI_BOOTSTRAP_SECRET='замените-на-длинный-случайный-секрет'
cargo run
```

Сервер слушает `127.0.0.1:3000` и создаёт SQLite-базу в `../data/hiddi.db`.
Для эксплуатации задайте сильный `HIDDI_BOOTSTRAP_SECRET` через менеджер
секретов и поместите сервер за reverse-proxy с HTTPS.

Подробности API: [`docs/api.md`](docs/api.md). Проектные решения и границы
безопасности: [`docs/architecture.md`](docs/architecture.md). План группового
MLS-шифрования: [`docs/groups-mls.md`](docs/groups-mls.md).
