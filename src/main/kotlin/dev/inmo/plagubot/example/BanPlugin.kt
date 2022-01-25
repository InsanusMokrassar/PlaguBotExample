package dev.inmo.plagubot.example

import com.benasher44.uuid.uuid4
import dev.inmo.micro_utils.coroutines.safelyWithResult
import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.exposed.onetomany.ExposedOneToManyKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.micro_utils.serialization.typed_serializer.TypedSerializer
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.config.database
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.edit.caption.editMessageCaption
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.oneOf
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.utils.marker_factories.ByUserMessageMarkerFactory
import dev.inmo.tgbotapi.extensions.utils.asFromUser
import dev.inmo.tgbotapi.extensions.utils.formatting.*
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.MessageEntity.textsources.*
import dev.inmo.tgbotapi.types.message.abstracts.*
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
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

@Polymorphic
sealed interface WorkMode {
    sealed interface EnabledForAdmins : WorkMode {
        @Serializable
        companion object Default : EnabledForAdmins
    }
    sealed interface EnabledForUsers : WorkMode {
        @Serializable
        companion object Default : EnabledForUsers
    }
    @Serializable
    object Enabled : WorkMode, EnabledForAdmins, EnabledForUsers
    @Serializable
    object Disabled : WorkMode
}
private val serializationModule = SerializersModule {
    polymorphic(WorkMode::class) {
        subclass(WorkMode.EnabledForAdmins.Default::class, WorkMode.EnabledForAdmins.serializer())
        subclass(WorkMode.EnabledForUsers.Default::class, WorkMode.EnabledForUsers.serializer())
        subclass(WorkMode.Enabled::class, WorkMode.Enabled.serializer())
        subclass(WorkMode.Disabled::class, WorkMode.Disabled.serializer())
    }
}

@Serializable
private data class ChatSettings(
    val warningsUntilBan: Int = 3,
    val allowWarnAdmins: Boolean = true,
    @Deprecated("use workMode instead")
    val enabled: Boolean = true,
    val workMode: WorkMode = if (enabled) WorkMode.Enabled else WorkMode.Disabled,
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
    serializersModule = serializationModule
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
    chatSettings: ChatSettings,
    fromAdmin: Boolean
): Boolean {
    return when (chatSettings.workMode) {
        WorkMode.Disabled -> {
            reply(
                sourceMessage,
                buildEntities(" ") {
                    +"Ban plugin is disabled in this chat. Use"
                    botCommand(enableCommand)
                    +"to enable ban plugin for everybody"
                }
            )
            false
        }
        WorkMode.Enabled -> true
        WorkMode.EnabledForAdmins -> {
            if (!fromAdmin) {
                reply(
                    sourceMessage,
                    buildEntities(" ") {
                        +"Ban plugin is disabled for users in this chat. Ask admins to use"
                        botCommand(enableCommand)
                        +"to enable ban plugin for everybody"
                    }
                )
                false
            } else {
                true
            }
        }
        WorkMode.EnabledForUsers -> {
            if (fromAdmin) {
                reply(
                    sourceMessage,
                    buildEntities(" ") {
                        +"Ban plugin is disabled for admins in this chat. Use"
                        botCommand(enableCommand)
                        +"to enable ban plugin for everybody"
                    }
                )
                false
            } else {
                true
            }
        }
    }
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
            "Enable ban plugin for current chat"
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
            fromMessage: Message,
            sentByAdmin: Boolean
        ): ChatSettings? {
            val chatSettings = chatsSettings.get(fromMessage.chat.id) ?: ChatSettings()
            return if (!checkBanPluginEnabled(fromMessage, chatSettings, sentByAdmin)) {
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
                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val sentByAdmin = commandMessage is AnonymousGroupContentMessage ||
                    (commandMessage is CommonGroupContentMessage && admins.any { it.user.id == commandMessage.user.id })
                val chatSettings = getChatSettings(commandMessage, sentByAdmin) ?: return@onCommand

                val userInReply = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user ?: return@onCommand // add handling
                if (sentByAdmin) {
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
                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val sentByAdmin = commandMessage is AnonymousGroupContentMessage ||
                    (commandMessage is CommonGroupContentMessage && admins.any { it.user.id == commandMessage.user.id })
                val chatSettings = getChatSettings(commandMessage, sentByAdmin) ?: return@onCommand

                val userInReply = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user ?: return@onCommand // add handling
                if (sentByAdmin) {
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
                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val sentByAdmin = commandMessage is AnonymousGroupContentMessage ||
                    (commandMessage is CommonGroupContentMessage && admins.any { it.user.id == commandMessage.user.id })
                val chatSettings = getChatSettings(commandMessage, sentByAdmin) ?: return@onCommand

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
                if (sentByAdmin) {
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
                    when (chatSettings.workMode) {
                        WorkMode.Disabled -> {
                            reply(commandMessage, "Ban plugin already disabled for this group")
                        }
                        WorkMode.Enabled -> {
                            val disableForUsers = uuid4().toString().take(12)
                            val disableForAdmins = uuid4().toString().take(12)
                            val disableForAll = uuid4().toString().take(12)
                            val keyboard = inlineKeyboard {
                                row {
                                    dataButton("Disable for admins", disableForAdmins)
                                    dataButton("Disable for all", disableForAll)
                                    dataButton("Disable for users", disableForUsers)
                                }
                            }
                            val messageWithKeyboard = reply(
                                commandMessage,
                                "Choose an option",
                                replyMarkup = keyboard
                            )
                            val answer = oneOf(
                                async {
                                    waitMessageDataCallbackQuery(
                                        count = 1,
                                        filter = {
                                            it.message.messageId == messageWithKeyboard.messageId &&
                                                it.message.chat.id == messageWithKeyboard.chat.id &&
                                                (if (commandMessage is AnonymousGroupContentMessage<*>) {
                                                    adminsApi.isAdmin(it.message.chat.id, it.user.id)
                                                } else {
                                                    it.user.id == commandMessage.asFromUser() ?.user ?.id
                                                }).also { userAllowed ->
                                                    if (!userAllowed) {
                                                        answer(it, "You are not allowed for this action", showAlert = true)
                                                    }
                                                } &&
                                                (it.data == disableForUsers || it.data == disableForAdmins || it.data == disableForAll)
                                        }
                                    ).firstOrNull() ?.data
                                },
                                async {
                                    delay(60_000L)
                                    null
                                }
                            )
                            when (answer) {
                                disableForUsers -> {
                                    chatsSettings.set(
                                        commandMessage.chat.id,
                                        chatSettings.copy(workMode = WorkMode.EnabledForAdmins)
                                    )
                                    editMessageText(
                                        messageWithKeyboard,
                                        "Disabled for users"
                                    )
                                }
                                disableForAdmins -> {
                                    chatsSettings.set(
                                        commandMessage.chat.id,
                                        chatSettings.copy(workMode = WorkMode.EnabledForUsers)
                                    )
                                    editMessageText(
                                        messageWithKeyboard,
                                        "Disabled for admins"
                                    )
                                }
                                disableForAll -> {
                                    chatsSettings.set(
                                        commandMessage.chat.id,
                                        chatSettings.copy(workMode = WorkMode.Disabled)
                                    )
                                    editMessageText(
                                        messageWithKeyboard,
                                        "Disabled for all"
                                    )
                                }
                                else -> {
                                    editMessageText(
                                        messageWithKeyboard,
                                        buildEntities {
                                            strikethrough(messageWithKeyboard.content.textSources) + "\n" + "It took too much time, dismissed"
                                        }
                                    )
                                }
                            }
                        }
                        WorkMode.EnabledForAdmins,
                        WorkMode.EnabledForUsers -> {
                            chatsSettings.set(
                                commandMessage.chat.id,
                                chatSettings.copy(workMode = WorkMode.Disabled)
                            )
                            reply(commandMessage, "Ban plugin has been disabled for this group")
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
                    when (chatSettings.workMode) {
                        WorkMode.Enabled -> {
                            reply(commandMessage, "Ban plugin already enabled for this group")
                        }
                        WorkMode.Disabled -> {
                            val enableForUsers = uuid4().toString().take(12)
                            val enableForAdmins = uuid4().toString().take(12)
                            val enableForAll = uuid4().toString().take(12)
                            val keyboard = inlineKeyboard {
                                row {
                                    dataButton("Enable for admins", enableForAdmins)
                                    dataButton("Enable for all", enableForAll)
                                    dataButton("Enable for users", enableForUsers)
                                }
                            }
                            val messageWithKeyboard = reply(
                                commandMessage,
                                "Choose an option",
                                replyMarkup = keyboard
                            )
                            val answer = oneOf(
                                async {
                                    waitMessageDataCallbackQuery(
                                        count = 1,
                                        filter = {
                                            it.message.messageId == messageWithKeyboard.messageId &&
                                                it.message.chat.id == messageWithKeyboard.chat.id &&
                                                (if (commandMessage is AnonymousGroupContentMessage<*>) {
                                                    adminsApi.isAdmin(it.message.chat.id, it.user.id)
                                                } else {
                                                    it.user.id == commandMessage.asFromUser() ?.user ?.id
                                                }).also { userAllowed ->
                                                    if (!userAllowed) {
                                                        answer(it, "You are not allowed for this action", showAlert = true)
                                                    }
                                                } &&
                                                (it.data == enableForUsers || it.data == enableForAdmins || it.data == enableForAll)
                                        }
                                    ).firstOrNull() ?.data
                                },
                                async {
                                    delay(60_000L)
                                    null
                                }
                            )
                            when (answer) {
                                enableForUsers -> {
                                    chatsSettings.set(
                                        commandMessage.chat.id,
                                        chatSettings.copy(workMode = WorkMode.EnabledForUsers)
                                    )
                                    editMessageText(
                                        messageWithKeyboard,
                                        "Enabled for users"
                                    )
                                }
                                enableForAdmins -> {
                                    chatsSettings.set(
                                        commandMessage.chat.id,
                                        chatSettings.copy(workMode = WorkMode.EnabledForAdmins)
                                    )
                                    editMessageText(
                                        messageWithKeyboard,
                                        "Enabled for admins"
                                    )
                                }
                                enableForAll -> {
                                    chatsSettings.set(
                                        commandMessage.chat.id,
                                        chatSettings.copy(workMode = WorkMode.Enabled)
                                    )
                                    editMessageText(
                                        messageWithKeyboard,
                                        "Enabled for all"
                                    )
                                }
                                else -> {
                                    editMessageText(
                                        messageWithKeyboard,
                                        buildEntities {
                                            strikethrough(messageWithKeyboard.content.textSources) + "\n" + "It took too much time, dismissed"
                                        }
                                    )
                                }
                            }
                        }
                        WorkMode.EnabledForAdmins,
                        WorkMode.EnabledForUsers -> {
                            chatsSettings.set(
                                commandMessage.chat.id,
                                chatSettings.copy(workMode = WorkMode.Enabled)
                            )
                            reply(commandMessage, "Ban plugin has been enabled for this group")
                        }
                    }
                } ?: reply(commandMessage, "You can't manage settings of ban plugin for this chat")
            }
        }

        onMessageDataCallbackQuery {
            it.message
        }
    }
}
