package dev.inmo.plagubot.example

import dev.inmo.micro_utils.pagination.FirstPagePagination
import dev.inmo.micro_utils.repos.KeyValuesRepo
import dev.inmo.micro_utils.repos.add
import dev.inmo.micro_utils.repos.exposed.onetomany.ExposedOneToManyKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.config.database
import dev.inmo.tgbotapi.extensions.api.chat.members.kickChatMember
import dev.inmo.tgbotapi.extensions.api.chat.members.restrictChatMember
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.utils.formatting.*
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.MessageEntity.textsources.mention
import dev.inmo.tgbotapi.types.message.abstracts.*
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database

private val oneElmentPagination = FirstPagePagination(1)
private val warningCommandRegex = Regex("(warn(ing)?)|(attention)")
private val warningCommands = listOf(
    "warn",
    "warning",
    "attention",
)

@Serializable
private data class ChatSettings(
    val warningsUntilBan: Int = 3
)
private typealias WarningsTable = KeyValuesRepo<Pair<ChatId, UserId>, MessageIdentifier>
private typealias ChatsSettingsTable = KeyValuesRepo<ChatId, ChatSettings>

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
    get() = ExposedOneToManyKeyValueRepo(
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

class BanPlugin : Plugin {
    override suspend fun getCommands(): List<BotCommand> = warningCommands.map {
        BotCommand(it, "Warn user about some violation") // in format \"/$it[ weight[ reason]?]?\"")
    }

    override suspend fun BehaviourContext.invoke(database: Database, params: Map<String, Any>) {
        val adminsApi = params.adminsPlugin ?.adminsAPI(params.database ?: return) ?: return
        val warningsRepository = database.warningsTable
        val chatsSettings = database.chatsSettingsTable

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
                    val settings = chatsSettings.get(commandMessage.chat.id, oneElmentPagination).results.firstOrNull() ?: ChatSettings().also {
                        chatsSettings.add(commandMessage.chat.id, it)
                    }
                    if (warnings >= settings.warningsUntilBan) {
                        kickChatMember(commandMessage.chat, userInReply)
                    } else {
                        reply(
                            commandMessage,
                            buildEntities {
                                mention("${userInReply.lastName}  ${userInReply.firstName}", userInReply)
                                regular(" You have ")
                                bold("${settings.warningsUntilBan - warnings}")
                                regular(" warnings until ban")
                            }
                        )
                    }
                } else {
                    reply(
                        commandMessage,
                        buildEntities {
                            admins.map {
                                mention("${it.user.lastName} ${it.user.firstName}", it.user.id)
                                regular(" ")
                            }
                        }
                    )
                }
            }
        }
    }
}