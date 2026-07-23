# Hiddi Android

Android-клиент Hiddi Messenger на Jetpack Compose. Поддерживает
регистрацию по invite-ключу, поиск по никнейму, личные диалоги,
Signal PQXDH/Double Ratchet, зашифрованную локальную историю и фоновый
приём системными уведомлениями. Отдельный экран настроек управляет публичным
видимым именем, описанием до 250 символов и очищенным JPEG-аватаром; эти
профильные поля видны серверу и не относятся к E2EE-контенту. Чужой профиль
открывается из поиска, аватара в списке диалогов и шапки личного чата.

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

## Подписанный APK

Release-сборка намеренно не использует debug-ключ и не собирается без HTTPS.
Ключ подписи храните вне репозитория и обязательно сделайте его резервную копию:
без того же ключа Android не разрешит обновлять уже установленное приложение.
Debug всегда использует `http://127.0.0.1:3000`, а release по умолчанию —
`https://hiddi.myaifriend.su`. Варианты устанавливаются рядом: production имеет
пакет `ru.hiddi.messenger`, а `Hiddi Dev` — `ru.hiddi.messenger.debug`. Их
аккаунты, локальные ключи, история и настройки не пересекаются.

Передайте Gradle путь к локальному ключу:

```bash
./gradlew :app:assembleRelease \
  -PhiddiKeystorePath=/absolute/path/to/release.jks \
  -PhiddiKeystorePasswordFile=/absolute/path/to/release-password.txt
```

По умолчанию используется alias `hiddi-release`; при необходимости его можно
заменить параметром `-PhiddiKeyAlias=...`. Файл пароля должен содержать только
пароль keystore. Другой production URL можно передать через
`-PhiddiServerUrl=https://...`. Пароль, keystore и собранные APK не коммитьте
в Git.
