package dev.inmo.plagubot.example

import dev.inmo.micro_utils.coroutines.launchSafelyWithoutExceptions
import dev.inmo.micro_utils.coroutines.safelyWithResult
import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.exposed.onetomany.ExposedOneToManyKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.config.database
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.utils.marker_factories.ByUserMessageMarkerFactory
import dev.inmo.tgbotapi.extensions.utils.formatting.*
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.MessageEntity.textsources.*
import dev.inmo.tgbotapi.types.message.abstracts.*
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database

private val disableCommandRegex = Regex("disable_ban_plugin")
private val enableCommandRegex = Regex("enable_ban_plugin")
private val warningCommandRegex = Regex("warn(ing)?")
private val unwarningCommandRegex = Regex("unwarn(ing)?")
private val setChatWarningsCountCommandRegex = Regex("set_chat_warnings_count")
private val setChatWarningsCountCommand = "set_chat_warnings_count"
private val warningCommands = listOf(
    "warn",
    "warning"
)
private val unwarningCommands = listOf(
    "unwarn",
    "unwarning"
)
private const val countWarningsCommand = "ban_count_warns"
private const val disableCommand = "disable_ban_plugin"
private const val enableCommand = "enable_ban_plugin"

@Serializable
private data class ChatSettings(
    val warningsUntilBan: Int = 3,
    val allowWarnAdmins: Boolean = true,
    val enabled: Boolean = true,
)
private typealias WarningsTable = KeyValuesRepo<Pair<ChatId, UserId>, MessageIdentifier>
private typealias ChatsSettingsTable = KeyValueRepo<ChatId, ChatSettings>

private val Database.warningsTable: WarningsTable
    get() = ExposedOneToManyKeyValueRepo(
        this,
        { text("chatToUser") },
        { long("messageId") },
        "BanPluginWarningsTable"
    ).withMapper<Pair<ChatId, UserId>, MessageIdentifier, String, Long>(
        keyToToFrom = { banPluginSerialFormat.decodeFromString(this) },
        keyFromToTo = { banPluginSerialFormat.encodeToString(this) },
        valueToToFrom = { this },
        valueFromToTo = { this }
    )

private val banPluginSerialFormat = Json {
    ignoreUnknownKeys = true
}
private val Database.chatsSettingsTable: ChatsSettingsTable
    get() = ExposedKeyValueRepo(
        this,
        { long("chatId") },
        { text("userId") },
        "BanPluginChatsSettingsTable"
    ).withMapper<ChatId, ChatSettings, Long, String>(
        keyToToFrom = { toChatId() },
        keyFromToTo = { chatId },
        valueToToFrom = { banPluginSerialFormat.decodeFromString(ChatSettings.serializer(), this) },
        valueFromToTo = { banPluginSerialFormat.encodeToString(ChatSettings.serializer(), this) }
    )
private suspend fun BehaviourContext.checkBanPluginEnabled(
    sourceMessage: Message,
    chatSettings: ChatSettings?
): Boolean {
    if (chatSettings ?.enabled == false) {
        reply(
            sourceMessage,
            buildEntities(" ") {
                +"Ban plugin is disabled in this chat. Use"
                botCommand(enableCommand)
                +"to enable ban plugin"
            }
        )
        return false
    }
    return true
}

@Serializable
class BanPlugin : Plugin {
    override suspend fun getCommands(): List<BotCommand> = warningCommands.map {
        BotCommand(it, "Warn user about some violation") // in format \"/$it[ weight[ reason]?]?\"")
    } + unwarningCommands.map {
        BotCommand(it, "Remove warning for user") // in format \"/$it[ weight[ reason]?]?\"")
    } + listOf(
        BotCommand(
            setChatWarningsCountCommand,
            "Set group chat warnings per user until his ban"
        ),
        BotCommand(
            countWarningsCommand,
            "Use with reply (or just call to get know about you) to get warnings count"
        ),
        BotCommand(
            disableCommand,
            "Disable ban plugin for current chat"
        ),
        BotCommand(
            enableCommand,
            "Disable ban plugin for current chat"
        )
    )

    override suspend fun BehaviourContext.invoke(database: Database, params: Map<String, Any>) {
        val adminsApi = params.adminsPlugin ?.adminsAPI(params.database ?: return) ?: return
        val warningsRepository = database.warningsTable
        val chatsSettings = database.chatsSettingsTable

        suspend fun sayUserHisWarnings(message: Message, userInReply: User, settings: ChatSettings, warnings: Long) {
            reply(
                message,
                buildEntities {
                    mention("${userInReply.lastName}  ${userInReply.firstName}", userInReply)
                    regular(" You have ")
                    bold("${settings.warningsUntilBan - warnings}")
                    regular(" warnings until ban")
                }
            )
        }
        suspend fun BehaviourContext.getChatSettings(
            fromMessage: Message
        ): ChatSettings? {
            val chatSettings = chatsSettings.get(fromMessage.chat.id) ?: ChatSettings()
            return if (!checkBanPluginEnabled(fromMessage, chatSettings)) {
                null
            } else {
                chatSettings
            }
        }

        onCommand(
            warningCommandRegex,
            requireOnlyCommandInMessage = false
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                val chatSettings = getChatSettings(commandMessage) ?: return@onCommand

                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val allowed = commandMessage is AnonymousGroupContentMessage ||
                    (commandMessage is CommonGroupContentMessage && admins.any { it.user.id == commandMessage.user.id })
                val userInReply = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user ?: return@onCommand // add handling
                if (allowed) {
                    val key = commandMessage.chat.id to userInReply.id
                    warningsRepository.add(key, commandMessage.messageId)
                    val warnings = warningsRepository.count(key)
                    val settings = chatSettings ?: ChatSettings().also {
                        chatsSettings.set(commandMessage.chat.id, it)
                    }
                    if (warnings >= settings.warningsUntilBan) {
                        val banned = safelyWithResult {
                            banChatMember(commandMessage.chat, userInReply)
                        }.getOrNull() ?: false
                        reply(
                            commandMessage,
                            buildEntities(" ") {
                                +"User" + userInReply.mention(userInReply.name) + "has${if (banned) " " else " not "}been banned"
                            }
                        )
                    } else {
                        sayUserHisWarnings(commandMessage, userInReply, settings, warnings)
                    }
                } else {
                    reply(
                        commandMessage,
                        buildEntities {
                            admins.filter {
                                it.user !is Bot
                            }.let { usersAdmins ->
                                usersAdmins.mapIndexed { i, it ->
                                    mention("${it.user.lastName} ${it.user.firstName}", it.user)
                                    if (usersAdmins.lastIndex != i) {
                                        regular(", ")
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
        onCommand(
            unwarningCommandRegex,
            requireOnlyCommandInMessage = false
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                val chatSettings = getChatSettings(commandMessage) ?: return@onCommand

                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val allowed = commandMessage is AnonymousGroupContentMessage ||
                    (commandMessage is CommonGroupContentMessage && admins.any { it.user.id == commandMessage.user.id })
                val userInReply = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user ?: return@onCommand // add handling
                if (allowed) {
                    val key = commandMessage.chat.id to userInReply.id
                    val warnings = warningsRepository.getAll(key)
                    if (warnings.isNotEmpty()) {
                        warningsRepository.clear(key)
                        warningsRepository.add(key, warnings.dropLast(1))
                        val settings = chatSettings ?: ChatSettings().also {
                            chatsSettings.set(commandMessage.chat.id, it)
                        }
                        sayUserHisWarnings(commandMessage, userInReply, settings, warnings.size - 1L)
                    } else {
                        reply(
                            commandMessage,
                            listOf(regular("User have no warns"))
                        )
                    }
                } else {
                    reply(
                        commandMessage,
                        listOf(regular("Sorry, you are not allowed for this action"))
                    )
                }
            }
        }
        onCommand(
            setChatWarningsCountCommandRegex,
            requireOnlyCommandInMessage = false,
            markerFactory = ByUserMessageMarkerFactory
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                val chatSettings = getChatSettings(commandMessage) ?: return@onCommand

                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val allowed = commandMessage is AnonymousGroupContentMessage ||
                    (commandMessage is CommonGroupContentMessage && admins.any { it.user.id == commandMessage.user.id })
                var newCount: Int? = null
                for (textSource in commandMessage.content.textSources.dropWhile { it !is BotCommandTextSource || it.command != setChatWarningsCountCommand }) {
                    val potentialCount = textSource.source.trim().toIntOrNull()
                    if (potentialCount != null) {
                        newCount = potentialCount
                        break
                    }
                }
                if (newCount == null || newCount < 1) {
                    reply(
                        commandMessage,
                        listOf(
                            regular("Usage: "),
                            code("/setChatWarningsCountCommand 3"),
                            regular(" (or any other number more than 0)")
                        )
                    )
                    return@onCommand
                }
                if (allowed) {
                    val settings = chatSettings ?: ChatSettings().also {
                        chatsSettings.set(commandMessage.chat.id, it)
                    }
                    chatsSettings.set(
                        commandMessage.chat.id,
                        settings.copy(warningsUntilBan = newCount)
                    )
                    reply(
                        commandMessage,
                        listOf(regular("Now warnings count is $newCount"))
                    )
                } else {
                    reply(
                        commandMessage,
                        listOf(regular("Sorry, you are not allowed for this action"))
                    )
                }
            }
        }

        onCommand(
            countWarningsCommand,
            requireOnlyCommandInMessage = true,
            markerFactory = ByUserMessageMarkerFactory
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                val replyMessage = commandMessage.replyTo
                val messageToSearch = replyMessage ?: commandMessage
                val user = when (messageToSearch) {
                    is CommonGroupContentMessage<*> -> messageToSearch.user
                    else -> {
                        reply(commandMessage, buildEntities { regular("Only common messages of users are allowed in reply for this command and to be called with this command") })
                        return@onCommand
                    }
                }
                val count = warningsRepository.count(messageToSearch.chat.id to user.id)
                val maxCount = (chatsSettings.get(messageToSearch.chat.id) ?: ChatSettings()).warningsUntilBan
                reply(
                    commandMessage,
                    regular("User ") + user.mention("${user.firstName} ${user.lastName}") + " have " + bold("$count/$maxCount") + " (" + bold("${maxCount - count}") + " left until ban)"
                )
            }
        }

        onCommand(disableCommandRegex, requireOnlyCommandInMessage = true) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                commandMessage.doAfterVerification(adminsApi) {
                    val chatSettings = chatsSettings.get(commandMessage.chat.id) ?: ChatSettings()
                    when (chatSettings.enabled) {
                        true -> {
                            chatsSettings.set(
                                commandMessage.chat.id,
                                chatSettings.copy(enabled = false)
                            )
                            reply(commandMessage, "Ban plugin has been disabled for this group")
                        }
                        false -> {
                            reply(commandMessage, "Ban plugin already disabled for this group")
                        }
                    }
                } ?: reply(commandMessage, "You can't manage settings of ban plugin for this chat")
            }
        }

        onCommand(enableCommandRegex, requireOnlyCommandInMessage = true) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                commandMessage.doAfterVerification(adminsApi) {
                    val chatId = commandMessage.chat.id
                    val chatSettings = chatsSettings.get(chatId) ?: ChatSettings()
                    when (chatSettings.enabled) {
                        false -> {
                            chatsSettings.set(chatId, chatSettings.copy(enabled = true))
                            reply(commandMessage, "Ban plugin has been enabled for this group")
                        }
                        true -> {
                            reply(commandMessage, "Ban plugin already enabled for this group")
                        }
                    }
                } ?: reply(commandMessage, "You can't manage settings of ban plugin for this chat")
            }
        }
    }
}
