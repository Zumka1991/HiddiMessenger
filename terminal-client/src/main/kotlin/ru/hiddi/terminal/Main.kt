package ru.hiddi.terminal

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "help", "--help", "-h", null -> printHelp()
        "register" -> RegisterCommand.run(args.drop(1))
        "chat" -> ChatCommand.send(args.drop(1))
        "inbox" -> ChatCommand.inbox(args.drop(1))
        "watch" -> ChatCommand.watch(args.drop(1))
        "shell" -> ChatCommand.shell(args.drop(1))
        "attachments" -> ChatCommand.attachments(args.drop(1))
        "export-attachment" -> ChatCommand.exportAttachment(args.drop(1))
        "ignore" -> ChatCommand.ignore(args.drop(1))
        "profile" -> ChatCommand.profile(args.drop(1))
        "group" -> GroupCommand.run(args.drop(1))
        else -> error("Неизвестная команда: ${args.first()}. Используйте `hiddi-terminal help`.")
    }
}

private fun printHelp() = println(
    """
    Hiddi Terminal — защищённый Signal-клиент для тестов и desktop-сценариев.

    Планируемые команды:
      register  Регистрация устройства и публикация PQXDH prekeys
      chat      Отправка E2EE-сообщения: --to nickname --message text
      inbox     Получение и расшифровка сообщений
      watch     Live-приём новых сообщений (foreground long polling)
      shell     Интерактивный режим: выбор диалога и отправка
      attachments  Список локальных зашифрованных вложений
      export-attachment  Явный экспорт: --id UUID --output PATH
      ignore    Игнор-лист: list, add --user nickname, remove --user nickname
      profile   Публичный профиль: show, update, avatar, delete-avatar
      group     MLS-группы: publish, create, list, send, inbox, watch, sync

    Ключи и сессии будут храниться локально; сервер получает только публичные
    prekeys и шифротексты.
    """.trimIndent(),
)
