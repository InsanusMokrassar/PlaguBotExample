# PlaguBotExample

This repository contains the sample of Telegram Bot based on [PlaguBot](https://github.com/InsanusMokrassar/PlaguBot).

## What can this bot do?

In case you will use [config.json](./config.json) as base config for your bot, you will get:

* Captcha
* Bans
* Welcome

In one bot.

## How does it work?

In [config.json](./config.json) you may see `plugins` section: it is the list of plugins you will include in your bot.
Besides, there are several other sections:

* `botToken` - the bot token
* `flushUpdates` - if this option set to `true`, after bot restart the accumulated updates will be skipped
* `clearCommands` - will force reset the commands of `bot` on each start
* `database` - settings of the database. You may use any database available with JDBC Technology (like `Postgres`). For
    more info see [DatabaseConfig.kt](https://github.com/InsanusMokrassar/PlaguBot/blob/master/bot/src/main/kotlin/dev/inmo/plagubot/config/DatabaseConfig.kt#L19)
