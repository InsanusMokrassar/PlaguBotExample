package dev.inmo.plagubot.example

import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.Plugin
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.core.Koin
import org.koin.core.module.Module

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

//    override suspend fun getCommands(): List<BotCommand> = listOf(
//        BotCommand(setWelcomeCommand, "Use with reply to the message which must be used as welcome message"),
//        BotCommand(unsetWelcomeCommand, "Use to remove welcome message"),
//    )
    override fun Module.setupDI(database: Database, params: JsonObject) {
        single { WelcomesTable(database) }
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val db = koin.get<WelcomesTable>()
        val adminsApi = koin.adminsPlugin ?.adminsAPI(koin.get()) ?: return

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
