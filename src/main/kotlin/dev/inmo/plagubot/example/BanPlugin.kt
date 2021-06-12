package dev.inmo.plagubot.example

import dev.inmo.micro_utils.coroutines.launchSafelyWithoutExceptions
import dev.inmo.micro_utils.pagination.firstPageWithOneElementPagination
import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.exposed.onetomany.ExposedOneToManyKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.config.database
import dev.inmo.tgbotapi.extensions.api.chat.members.kickChatMember
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.utils.formatting.*
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.MessageEntity.textsources.*
import dev.inmo.tgbotapi.types.message.abstracts.*
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database

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

@Serializable
private data class ChatSettings(
    val warningsUntilBan: Int = 3,
    val allowWarnAdmins: Boolean = true,
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

        onCommand(
            warningCommandRegex,
            requireOnlyCommandInMessage = false
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val allowed = commandMessage is AnonymousGroupContentMessage ||
                    (commandMessage is CommonGroupContentMessage && admins.any { it.user.id == commandMessage.user.id })
                val userInReply = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user ?: return@onCommand // add handling
                if (allowed) {
                    val key = commandMessage.chat.id to userInReply.id
                    warningsRepository.add(key, commandMessage.messageId)
                    val warnings = warningsRepository.count(key)
                    val settings = chatsSettings.get(commandMessage.chat.id) ?: ChatSettings().also {
                        chatsSettings.set(commandMessage.chat.id, it)
                    }
                    if (warnings >= settings.warningsUntilBan) {
                        kickChatMember(commandMessage.chat, userInReply)
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
                        val settings = chatsSettings.get(commandMessage.chat.id) ?: ChatSettings().also {
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
            requireOnlyCommandInMessage = false
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
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
                    val settings = chatsSettings.get(commandMessage.chat.id) ?: ChatSettings().also {
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
            requireOnlyCommandInMessage = true
        ) { commandMessage ->
            launchSafelyWithoutExceptions {
                if (commandMessage is GroupContentMessage<TextContent>) {
                    val replyMessage = commandMessage.replyTo
                    val messageToSearch = replyMessage ?: commandMessage
                    val user = when (messageToSearch) {
                        is CommonGroupContentMessage<*> -> messageToSearch.user
                        else -> {
                            reply(commandMessage, buildEntities { regular("Only common messages of users are allowed in reply for this command and to be called with this command") })
                            return@launchSafelyWithoutExceptions
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
        }
    }
}
