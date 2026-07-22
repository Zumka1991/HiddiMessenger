# Hiddi Android

Android-клиент Hiddi Messenger на Jetpack Compose. Поддерживает
регистрацию по invite-ключу, поиск по никнейму, личные диалоги,
Signal PQXDH/Double Ratchet, зашифрованную локальную историю и фоновый
приём системными уведомлениями.

## Требования

* JDK 17;
* Android Studio с Android SDK Platform 35;
* Android-устройство с API 29 или новее.

Открывайте каталог `android/` в Android Studio или собирайте из терминала:

```bash
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

## Безопасность

Приватные identity/prekey/ratchet-записи и история шифруются ключами
Android Keystore. Фоновое уведомление не содержит текст сообщения.
В debug-сборке разрешён `http://127.0.0.1:3000` через `adb reverse`; production-сборка
должна использовать только HTTPS.
