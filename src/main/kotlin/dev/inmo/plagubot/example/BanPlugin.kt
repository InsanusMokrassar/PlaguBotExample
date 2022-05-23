package dev.inmo.plagubot.example

import dev.inmo.micro_utils.common.*
import dev.inmo.micro_utils.coroutines.*
import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.exposed.onetomany.ExposedOneToManyKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.example.utils.extractChatIdAndData
import dev.inmo.plagubot.example.utils.settingsDataButton
import dev.inmo.tgbotapi.abstracts.FromUser
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.chat.members.*
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.utils.marker_factories.ByUserMessageMarkerFactory
import dev.inmo.tgbotapi.extensions.utils.asUser
import dev.inmo.tgbotapi.extensions.utils.formatting.*
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.libraries.cache.admins.*
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.message.textsources.*
import dev.inmo.tgbotapi.types.chat.*
import dev.inmo.tgbotapi.types.chat.Bot
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.chat.member.AdministratorChatMember
import dev.inmo.tgbotapi.types.message.abstracts.*
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.jetbrains.exposed.sql.Database
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named

private val warningCommandRegex = Regex("warn(ing)?")
private val banCommandRegex = Regex("ban")
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
private const val banCommand = "ban"

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
    private val toggleForUserData = "userToggle"
    private val toggleForAdminData = "adminToggle"
    private val allowWarnAdminsData = "allowWarnAdmins"
    private val warnsCountData = "warns"

//    override suspend fun getCommands(): List<BotCommand> = warningCommands.map {
//        BotCommand(it, "Warn user about some violation") // in format \"/$it[ weight[ reason]?]?\"")
//    } + unwarningCommands.map {
//        BotCommand(it, "Remove warning for user") // in format \"/$it[ weight[ reason]?]?\"")
//    } + listOf(
//        BotCommand(
//            setChatWarningsCountCommand,
//            "Set group chat warnings per user until his ban"
//        ),
//        BotCommand(
//            countWarningsCommand,
//            "Use with reply (or just call to get know about you) to get warnings count"
//        ),
//        BotCommand(
//            disableCommand,
//            "Disable ban plugin for current chat"
//        ),
//        BotCommand(
//            enableCommand,
//            "Enable ban plugin for current chat"
//        ),
//        BotCommand(
//            banCommand,
//            "Ban user in reply"
//        )
//    )

    private suspend fun BehaviourContext.updateSettings(
        adminsApi: AdminsCacheAPI,
        chatsSettings: ChatsSettingsTable,
        messageDataCallbackQuery: MessageDataCallbackQuery
    ): ChatId? {
        val (chatId, data) = extractChatIdAndData(messageDataCallbackQuery.data)
        val userId = messageDataCallbackQuery.user.id

        if (!adminsApi.isAdmin(chatId, userId)) {
            return null
        }

        var needNewMessage = false

        val settings = chatsSettings.get(chatId) ?: ChatSettings()

        chatsSettings.set(
            chatId,
            settings.copy(
                workMode = when (data) {
                    toggleForUserData -> when (settings.workMode) {
                        WorkMode.Disabled -> WorkMode.EnabledForUsers
                        WorkMode.Enabled -> WorkMode.EnabledForAdmins
                        WorkMode.EnabledForAdmins -> WorkMode.Enabled
                        WorkMode.EnabledForUsers -> WorkMode.Disabled
                    }
                    toggleForAdminData -> when (settings.workMode) {
                        WorkMode.Disabled -> WorkMode.EnabledForAdmins
                        WorkMode.Enabled -> WorkMode.EnabledForUsers
                        WorkMode.EnabledForAdmins -> WorkMode.Disabled
                        WorkMode.EnabledForUsers -> WorkMode.Enabled
                    }
                    else -> settings.workMode
                },
                warningsUntilBan = when (data) {
                    warnsCountData -> {
                        needNewMessage = true
                        oneOf(
                            parallel {
                                waitTextMessage (
                                    SendTextMessage(
                                        userId,
                                        buildEntities {
                                            +"Type count of warns until ban or "
                                            botCommand("cancel")
                                        }
                                    )
                                ).filter { message ->
                                    (message.content.text.toIntOrNull() != null).also { passed ->
                                        if (!passed) {
                                            reply(
                                                message,
                                                buildEntities {
                                                    +"You should type some number instead or "
                                                    botCommand("cancel")
                                                    +" instead of \""
                                                    +message.content.textSources
                                                    +"\""
                                                }
                                            )
                                        }
                                    }
                                }.first().content.text.toIntOrNull()
                            },
                            parallel {
                                waitText().filter {
                                    it.textSources.any {
                                        it is BotCommandTextSource && it.command == "cancel"
                                    }
                                }.first()
                                sendMessage(userId, "Canceled")
                                null // if received command with cancel - just return null and next ?: will cancel everything
                            }
                        ) ?: return null
                    }
                    else -> settings.warningsUntilBan
                },
                allowWarnAdmins = when (data) {
                    allowWarnAdminsData -> {
                        !settings.allowWarnAdmins
                    }
                    else -> settings.allowWarnAdmins
                }
            )
        )

        if (needNewMessage) {
            reply(messageDataCallbackQuery.message, "Updated")
        }

        answer(messageDataCallbackQuery, "Settings have been updated")

        return chatId
    }

    override fun Module.setupDI(database: Database, params: JsonObject) {
        single(named("warningsTable")) { database.warningsTable }
        single(named("chatsSettingsTable")) { database.chatsSettingsTable }
        single<SettingsProvider>(named("BanPluginSettingsProvider")) {
            val chatsSettings = get<ChatsSettingsTable>(named("chatsSettingsTable"))

            object : SettingsProvider {
                override val name: String
                    get() = "BanPlugin"
                override val id: String
                    get() = "BanPlugin"
                val Boolean.enabledSymbol
                    get() = if (this) {
                        "✅"
                    } else {
                        "❌"
                    }

                override suspend fun BehaviourContext.drawSettings(
                    chatId: ChatId,
                    userId: UserId,
                    messageId: MessageIdentifier
                ) {
                    val adminsApi = adminsPlugin ?.adminsAPI(get()) ?: return
                    val settings = chatsSettings.get(chatId) ?: ChatSettings()

                    if (!adminsApi.isAdmin(chatId, userId)) {
                        editMessageText(userId, messageId, "Ban settings are not supported for common users")
                        return
                    }

                    runCatchingSafely {
                        editMessageReplyMarkup(
                            userId,
                            messageId,
                            replyMarkup = inlineKeyboard {
                                row {
                                    val forUsersEnabled = settings.workMode is WorkMode.EnabledForUsers
                                    val usersEnabledSymbol = forUsersEnabled.enabledSymbol
                                    settingsDataButton(
                                        "$usersEnabledSymbol Users",
                                        chatId,
                                        toggleForUserData
                                    )
                                    val forAdminsEnabled = settings.workMode is WorkMode.EnabledForAdmins
                                    val adminsEnabledSymbol = forAdminsEnabled.enabledSymbol
                                    settingsDataButton(
                                        "$adminsEnabledSymbol Admins",
                                        chatId,
                                        toggleForAdminData
                                    )
                                }
                                row {
                                    settingsDataButton(
                                        "${settings.allowWarnAdmins.enabledSymbol} Warn admins",
                                        chatId,
                                        allowWarnAdminsData
                                    )
                                }
                                row {
                                    settingsDataButton(
                                        "Warns count: ${settings.warningsUntilBan}",
                                        chatId,
                                        warnsCountData
                                    )
                                }
                            }
                        )
                    }
                    val messageDataCallbackQuery = waitMessageDataCallbackQuery().filter {
                        it.user.id == userId && it.message.chat.id == userId && it.message.messageId == messageId
                    }.firstOrNull() ?: return

                    updateSettings(adminsApi, chatsSettings, messageDataCallbackQuery)

                    drawSettings(chatId, userId, messageDataCallbackQuery.message.messageId)
                }
            }
        }
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val warningsRepository = koin.get<WarningsTable>(named("warningsTable"))
        val chatsSettings = koin.get<ChatsSettingsTable>(named("chatsSettingsTable"))
        val settingsProvider = koin.get<SettingsProvider>(named("BanPluginSettingsProvider"))
        koin.settingsPlugin ?.register(settingsProvider)
        val adminsApi = koin.adminsPlugin ?.adminsAPI(koin.get()) ?: return

        suspend fun sayUserHisWarnings(message: Message, userInReply: Either<User, ChannelChat>, settings: ChatSettings, warnings: Long) {
            reply(
                message,
                buildEntities {
                    userInReply.onFirst { userInReply ->
                        mention("${userInReply.lastName}  ${userInReply.firstName}", userInReply)
                    }.onSecond {
                        +it.title
                    }
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
                val sentByAdmin = adminsApi.verifyMessageFromAdmin(commandMessage)
                val chatSettings = getChatSettings(commandMessage, sentByAdmin) ?: return@onCommand

                val userInReply = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user
                val channelInReply = (commandMessage.replyTo as? UnconnectedFromChannelGroupContentMessage<*>) ?.channel
                val chatId = userInReply ?.id ?: channelInReply ?.id ?: return@onCommand // add handling
                val userInReplyIsAnAdmin = let {
                    userInReply != null && (admins.any { it.user.id == userInReply.id } || (userInReply is Bot && getChatMember(commandMessage.chat.id, userInReply.id) is AdministratorChatMember))
                }

                if (sentByAdmin) {
                    if (!chatSettings.allowWarnAdmins && userInReply != null && userInReplyIsAnAdmin) {
                        reply(commandMessage, regular("User ") + userInReply.mention(userInReply.name) + " can't be warned - he is an admin")
                        return@onCommand
                    }
                    val key = commandMessage.chat.id to chatId
                    warningsRepository.add(key, commandMessage.messageId)
                    val warnings = warningsRepository.count(key)
                    if (warnings >= chatSettings.warningsUntilBan) {
                        var banned = false
                        when {
                            userInReply != null -> {
                                banned = safelyWithResult {
                                    banChatMember(commandMessage.chat, userInReply)
                                }.isSuccess
                                reply(
                                    commandMessage,
                                    buildEntities(" ") {
                                        +"User" + userInReply.mention(userInReply.name) + "has${if (banned) " " else " not "}been banned"
                                    }
                                )
                            }
                            channelInReply != null -> {
                                banned = safelyWithResult {
                                    banChatSenderChat(commandMessage.chat, channelInReply.id)
                                }.isSuccess
                                reply(
                                    commandMessage,
                                    buildEntities(" ") {
                                        +"Channel ${channelInReply.title} has${if (banned) " " else " not "}been banned"
                                    }
                                )
                            }
                        }
                        if (banned) {
                            warningsRepository.clear(key)
                        }
                    } else {
                        sayUserHisWarnings(commandMessage, (userInReply ?: channelInReply) ?.either() ?: return@onCommand, chatSettings, warnings)
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

                val userInReply = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user
                val channelInReply = (commandMessage.replyTo as? UnconnectedFromChannelGroupContentMessage<*>) ?.channel
                val chatId = userInReply ?.id ?: channelInReply ?.id ?: return@onCommand // add handling
                val userOrChannel: Either<User, ChannelChat> = (userInReply ?: channelInReply) ?.either() ?: return@onCommand

                if (sentByAdmin) {
                    val key = commandMessage.chat.id to chatId
                    val warnings = warningsRepository.getAll(key)
                    if (warnings.isNotEmpty()) {
                        warningsRepository.clear(key)
                        warningsRepository.add(key, warnings.dropLast(1))
                        sayUserHisWarnings(commandMessage, userOrChannel, chatSettings, warnings.size - 1L)
                    } else {
                        reply(
                            commandMessage,
                            listOf(regular("User or channel have no warns"))
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
                    chatsSettings.set(
                        commandMessage.chat.id,
                        chatSettings.copy(warningsUntilBan = newCount)
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
                    is UnconnectedFromChannelGroupContentMessage<*> -> messageToSearch.channel
                    else -> {
                        reply(commandMessage, buildEntities { regular("Only common messages of users are allowed in reply for this command and to be called with this command") })
                        return@onCommand
                    }
                }
                val count = warningsRepository.count(messageToSearch.chat.id to user.id)
                val maxCount = (chatsSettings.get(messageToSearch.chat.id) ?: ChatSettings()).warningsUntilBan
                val mention = (user.asUser()) ?.let {
                    it.mention("${it.firstName} ${it.lastName}")
                } ?: (user as? ChannelChat) ?.title ?.let(::regular) ?: return@onCommand
                reply(
                    commandMessage,
                    regular("User ") + mention + " have " + bold("$count/$maxCount") + " (" + bold("${maxCount - count}") + " left until ban)"
                )
            }
        }

//        onCommand(disableCommandRegex, requireOnlyCommandInMessage = true) { commandMessage ->
//            if (commandMessage is GroupContentMessage<TextContent>) {
//                commandMessage.doAfterVerification(adminsApi) {
//                    val chatSettings = chatsSettings.get(commandMessage.chat.id) ?: ChatSettings()
//                    when (chatSettings.workMode) {
//                        WorkMode.Disabled -> {
//                            reply(commandMessage, "Ban plugin already disabled for this group")
//                        }
//                        WorkMode.Enabled -> {
//                            val disableForUsers = uuid4().toString().take(12)
//                            val disableForAdmins = uuid4().toString().take(12)
//                            val disableForAll = uuid4().toString().take(12)
//                            val keyboard = inlineKeyboard {
//                                row {
//                                    dataButton("Disable for admins", disableForAdmins)
//                                    dataButton("Disable for all", disableForAll)
//                                    dataButton("Disable for users", disableForUsers)
//                                }
//                            }
//                            val messageWithKeyboard = reply(
//                                commandMessage,
//                                "Choose an option",
//                                replyMarkup = keyboard
//                            )
//                            val answer = oneOf(
//                                async {
//                                    waitMessageDataCallbackQuery(
//                                        count = 1,
//                                        filter = {
//                                            it.message.messageId == messageWithKeyboard.messageId &&
//                                                it.message.chat.id == messageWithKeyboard.chat.id &&
//                                                (if (commandMessage is AnonymousGroupContentMessage<*>) {
//                                                    adminsApi.isAdmin(it.message.chat.id, it.user.id)
//                                                } else {
//                                                    it.user.id == commandMessage.asFromUser() ?.user ?.id
//                                                }).also { userAllowed ->
//                                                    if (!userAllowed) {
//                                                        answer(it, "You are not allowed for this action", showAlert = true)
//                                                    }
//                                                } &&
//                                                (it.data == disableForUsers || it.data == disableForAdmins || it.data == disableForAll)
//                                        }
//                                    ).firstOrNull() ?.data
//                                },
//                                async {
//                                    delay(60_000L)
//                                    null
//                                }
//                            )
//                            when (answer) {
//                                disableForUsers -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.EnabledForAdmins)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Disabled for users"
//                                    )
//                                }
//                                disableForAdmins -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.EnabledForUsers)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Disabled for admins"
//                                    )
//                                }
//                                disableForAll -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.Disabled)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Disabled for all"
//                                    )
//                                }
//                                else -> {
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        buildEntities {
//                                            strikethrough(messageWithKeyboard.content.textSources) + "\n" + "It took too much time, dismissed"
//                                        }
//                                    )
//                                }
//                            }
//                        }
//                        WorkMode.EnabledForAdmins,
//                        WorkMode.EnabledForUsers -> {
//                            chatsSettings.set(
//                                commandMessage.chat.id,
//                                chatSettings.copy(workMode = WorkMode.Disabled)
//                            )
//                            reply(commandMessage, "Ban plugin has been disabled for this group")
//                        }
//                    }
//                } ?: reply(commandMessage, "You can't manage settings of ban plugin for this chat")
//            }
//        }
//
//        onCommand(enableCommandRegex, requireOnlyCommandInMessage = true) { commandMessage ->
//            if (commandMessage is GroupContentMessage<TextContent>) {
//                commandMessage.doAfterVerification(adminsApi) {
//                    val chatId = commandMessage.chat.id
//                    val chatSettings = chatsSettings.get(chatId) ?: ChatSettings()
//                    when (chatSettings.workMode) {
//                        WorkMode.Enabled -> {
//                            reply(commandMessage, "Ban plugin already enabled for this group")
//                        }
//                        WorkMode.Disabled -> {
//                            val enableForUsers = uuid4().toString().take(12)
//                            val enableForAdmins = uuid4().toString().take(12)
//                            val enableForAll = uuid4().toString().take(12)
//                            val keyboard = inlineKeyboard {
//                                row {
//                                    dataButton("Enable for admins", enableForAdmins)
//                                    dataButton("Enable for all", enableForAll)
//                                    dataButton("Enable for users", enableForUsers)
//                                }
//                            }
//                            val messageWithKeyboard = reply(
//                                commandMessage,
//                                "Choose an option",
//                                replyMarkup = keyboard
//                            )
//                            val answer = oneOf(
//                                async {
//                                    waitMessageDataCallbackQuery(
//                                        count = 1,
//                                        filter = {
//                                            it.message.messageId == messageWithKeyboard.messageId &&
//                                                it.message.chat.id == messageWithKeyboard.chat.id &&
//                                                (if (commandMessage is AnonymousGroupContentMessage<*>) {
//                                                    adminsApi.isAdmin(it.message.chat.id, it.user.id)
//                                                } else {
//                                                    it.user.id == commandMessage.asFromUser() ?.user ?.id
//                                                }).also { userAllowed ->
//                                                    if (!userAllowed) {
//                                                        answer(it, "You are not allowed for this action", showAlert = true)
//                                                    }
//                                                } &&
//                                                (it.data == enableForUsers || it.data == enableForAdmins || it.data == enableForAll)
//                                        }
//                                    ).firstOrNull() ?.data
//                                },
//                                async {
//                                    delay(60_000L)
//                                    null
//                                }
//                            )
//                            when (answer) {
//                                enableForUsers -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.EnabledForUsers)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Enabled for users"
//                                    )
//                                }
//                                enableForAdmins -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.EnabledForAdmins)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Enabled for admins"
//                                    )
//                                }
//                                enableForAll -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.Enabled)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Enabled for all"
//                                    )
//                                }
//                                else -> {
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        buildEntities {
//                                            strikethrough(messageWithKeyboard.content.textSources) + "\n" + "It took too much time, dismissed"
//                                        }
//                                    )
//                                }
//                            }
//                        }
//                        WorkMode.EnabledForAdmins,
//                        WorkMode.EnabledForUsers -> {
//                            chatsSettings.set(
//                                commandMessage.chat.id,
//                                chatSettings.copy(workMode = WorkMode.Enabled)
//                            )
//                            reply(commandMessage, "Ban plugin has been enabled for this group")
//                        }
//                    }
//                } ?: reply(commandMessage, "You can't manage settings of ban plugin for this chat")
//            }
//        }

        onCommand(banCommandRegex, requireOnlyCommandInMessage = true) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                commandMessage.doAfterVerification(adminsApi) {
                    val chatId = commandMessage.chat.id
                    val chatSettings = chatsSettings.get(chatId) ?: ChatSettings()
                    if (chatSettings.workMode is WorkMode.EnabledForAdmins) {
                        val userInReplyMessage = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user
                        // TODO() fix this in future userInReply check on top code cuz was no time, need sleep
                        val channelInReply = (commandMessage.replyTo as? UnconnectedFromChannelGroupContentMessage<*>) ?.channel
                        val chatUserId = userInReplyMessage ?.id ?: channelInReply ?.id ?: return@doAfterVerification
                        val key = commandMessage.chat.id to chatUserId

                        val userInReply: Either<User, ChannelChat> = when (val reply = commandMessage.replyTo) {
                            is FromUser -> reply.from
                            is UnconnectedFromChannelGroupContentMessage<*> -> reply.channel
                            else -> {
                                reply(commandMessage, "Use with reply to some message for user ban")
                                return@doAfterVerification
                            }
                        }.either()

                        val banned = safelyWithResult {
                            userInReply.onFirst {
                                banChatMember(commandMessage.chat, it.id)
                            }.onSecond {
                                banChatSenderChat(commandMessage.chat, it.id)
                            }
                        }.isSuccess

                        if (banned) {
                            warningsRepository.clear(key)
                            val mention = userInReply.mapOnFirst {
                                mention(it) // TODO("fix it in future no nickname in message from line: 840")
                            } ?: userInReply.mapOnSecond {
                                regular(it.title)
                            } ?: return@doAfterVerification
                            reply(
                                commandMessage,
                                buildEntities {
                                    +"User " + mention + " has been banned"
                                }
                            )
                        }
                    }
                }
            }
        }

        onMessageDataCallbackQuery {
            val chatId = updateSettings(adminsApi, chatsSettings, it) ?: return@onMessageDataCallbackQuery

            with(settingsProvider) {
                drawSettings(chatId, it.user.id, it.message.messageId)
            }
        }
    }
}
