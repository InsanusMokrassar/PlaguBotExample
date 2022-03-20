package dev.inmo.plagubot.example

import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.exposed.onetomany.ExposedKeyValuesRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.config.database
import dev.inmo.tgbotapi.extensions.api.send.copyMessage
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.MessageEntity.textsources.TextSource
import dev.inmo.tgbotapi.types.MessageEntity.textsources.TextSourceSerializer
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.content.abstracts.MessageContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.jetbrains.exposed.sql.Database

private class WelcomesTable(
    database: Database
) : KeyValueRepo<ChatId, MessageIdentifier> by ExposedKeyValueRepo(
    database,
    { long("chatId") },
    { long("messageId") },
    "WelcomesTable"
).withMapper<ChatId, MessageIdentifier, Long, MessageIdentifier>(
    keyFromToTo = { chatId },
    keyToToFrom = { ChatId(this) },
    valueToToFrom = { this },
    valueFromToTo = { this }
)

@kotlinx.serialization.Serializable
class WelcomePlugin : Plugin {
    private val setWelcomeCommand = "welcome"
    private val unsetWelcomeCommand = "remove_welcome"

    override suspend fun getCommands(): List<BotCommand> = listOf(
        BotCommand(setWelcomeCommand, "Use with reply to the message which must be used as welcome message"),
        BotCommand(unsetWelcomeCommand, "Use to remove welcome message"),
    )
    override suspend fun BehaviourContext.invoke(database: Database, params: Map<String, Any>) {
        val db = WelcomesTable(database)
        val adminsApi = params.adminsPlugin ?.adminsAPI(params.database ?: return) ?: return

        onCommand(setWelcomeCommand, requireOnlyCommandInMessage = true) {
            it.doAfterVerification(adminsApi) {

                when (val reply = it.replyTo) {
                    null -> reply(it, "You should reply to the message to set it as a welcome message. Do not delete that message in future")
                    is ContentMessage<*> -> {
                        db.set(it.chat.id, reply.messageId)
                        reply(it, "The message has been set as a welcome message")
                    }
                    else -> reply(it, "Only messages with content are allowed as welcome messages")
                }
            }
        }

        onCommand(unsetWelcomeCommand, requireOnlyCommandInMessage = true) {
            it.doAfterVerification(adminsApi) {
                if (db.contains(it.chat.id)) {
                    db.unset(it.chat.id)
                    reply(it, "You have removed welcome message for this chat")
                } else {
                    reply(it, "There is no welcome message in this chat")
                }
            }
        }

        onNewChatMembers {
            val welcomeMessageId = db.get(it.chat.id) ?: return@onNewChatMembers
            reply(it, it.chat.id, welcomeMessageId)
        }
    }
}
