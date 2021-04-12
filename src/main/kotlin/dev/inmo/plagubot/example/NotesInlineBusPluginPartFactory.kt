package dev.inmo.plagubot.example

import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.micro_utils.repos.set
import dev.inmo.plagubot.config.database
import dev.inmo.tgbotapi.CommonAbstracts.TextSource
import dev.inmo.tgbotapi.CommonAbstracts.textSources
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitText
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.command
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.utils.extensions.parseCommandsWithParams
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.InlineQueries.InlineQueryResult.InlineQueryResultArticle
import dev.inmo.tgbotapi.types.InlineQueries.InlineQueryResult.abstracts.InlineQueryResult
import dev.inmo.tgbotapi.types.InlineQueries.InputMessageContent.InputTextMessageContent
import dev.inmo.tgbotapi.types.MessageEntity.textsources.TextSourceSerializer
import dev.inmo.tgbotapi.types.User
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.FromUserMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.cbor.Cbor
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.statements.api.ExposedBlob

typealias TextNoteKey = String
fun User.textNoteKey(keyword: String) = "${id.chatId}_$keyword"

private val textNotesSavingFormat = Cbor {  }
private val textSourcesSerializer = ListSerializer(TextSourceSerializer)

class NotesTextInlineBusPart(
    database: Database
) : InlineBusPluginPart {
    private val textNotesTable = ExposedKeyValueRepo<TextNoteKey, ExposedBlob>(
        database,
        { text("key") },
        { blob("bytes") },
        "NotesInlineBusPartTextsTable"
    ).withMapper<TextNoteKey, List<TextSource>, TextNoteKey, ExposedBlob>(
        { this },
        { ExposedBlob(textNotesSavingFormat.encodeToByteArray(textSourcesSerializer, this)) },
        { this },
        { textNotesSavingFormat.decodeFromByteArray(textSourcesSerializer, bytes) }
    )
    override suspend fun getResults(user: User, query: String): List<InlineQueryResult> {
        val key = user.textNoteKey(query)
        return textNotesTable.get(user.textNoteKey(query)) ?.let {
            listOf(
                InlineQueryResultArticle(
                    key,
                    query,
                    InputTextMessageContent(it)
                )
            )
        } ?: emptyList()
    }

    suspend fun saveNote(user: User, name: String, parts: List<TextSource>) {
        textNotesTable.set(user.textNoteKey(name), parts)
    }
}

const val SaveNoteCommand = "save_as_note"

@Serializable
class NotesInlineBusPluginPartFactory : InlineBusPluginPartFactory {
    override val additionalCommands: List<BotCommand>
        get() = listOf(
            BotCommand(SaveNoteCommand, "Use with reply to message to save as note for inline autocomplete")
        )

    override suspend fun BehaviourContext.createParts(
        database: Database,
        params: Map<String, Any>
    ): List<InlineBusPluginPart> {
        val db = params.database ?: database
        val textPart = NotesTextInlineBusPart(db)
        command(Regex(SaveNoteCommand), requireOnlyCommandInMessage = false) { commandMessage ->
            val repliedTo = commandMessage.replyTo
            if (repliedTo == null || repliedTo !is ContentMessage<*>) {
                reply(commandMessage, "Reply to message and send me /$SaveNoteCommand to save note")
                return@command
            }
            val content = repliedTo.content
            if (content !is TextContent) {
                reply(commandMessage, "Unfortunately, currently supported only text messages")
                return@command
            }
            val asSentFromUser = (commandMessage as? FromUserMessage) ?: let { _ ->
                reply(commandMessage, "Unfortunately, I can't recognise user from message. Send me message in private or as common user in group")
                return@command
            }
            val user = asSentFromUser.user
            var title = commandMessage.parseCommandsWithParams()[SaveNoteCommand] ?.firstOrNull()
            if (title.isNullOrBlank()) {
                reply(commandMessage, "Ok, send me title for note")
                title = waitText {
                    if (commandMessage.chat.id == this.chat.id && (this as? FromUserMessage) ?.user ?.id == user.id) {
                        this.content
                    } else {
                        null
                    }
                }.first().text
            }
            println(title)
            textPart.saveNote(user, title, content.textSources)
            reply(commandMessage, "Note saved with title $title")
        }
        return listOf(
            textPart
        )
    }

}
