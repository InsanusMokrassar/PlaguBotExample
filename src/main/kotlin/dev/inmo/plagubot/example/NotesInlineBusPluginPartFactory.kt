package dev.inmo.plagubot.example

import dev.inmo.micro_utils.pagination.*
import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.config.database
import dev.inmo.tgbotapi.CommonAbstracts.*
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitText
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.command
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.utils.asContentMessage
import dev.inmo.tgbotapi.extensions.utils.asFromUserMessage
import dev.inmo.tgbotapi.extensions.utils.extensions.parseCommandsWithParams
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.InlineQueries.InlineQueryResult.InlineQueryResultArticle
import dev.inmo.tgbotapi.types.InlineQueries.InlineQueryResult.abstracts.InlineQueryResult
import dev.inmo.tgbotapi.types.InlineQueries.InputMessageContent.InputTextMessageContent
import dev.inmo.tgbotapi.types.MessageEntity.textsources.TextSourceSerializer
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.FromUserMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.cbor.Cbor
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.UpdateStatement
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction

typealias TextNoteKey = Pair<Identifier, String>
typealias TextNoteId = Long
fun User.textNoteKey(keyword: String) = "${id.chatId}_$keyword"
fun TextNoteKey.textNoteKey() = "${first}_$second"

private val textNotesSavingFormat = Cbor {  }
private val textSourcesSerializer = ListSerializer(TextSourceSerializer)

@Serializable
data class TextNoteInfo(
    val owner: Identifier,
    val keyword: String,
    val sources: List<@Serializable(TextSourceSerializer::class) TextSource>
) {
    val key by lazy {
        TextNoteKey(owner, keyword)
    }
}

private val defaultNotesPage = FirstPagePagination(12)

private interface NotesTextInlineBusPartRepo : CRUDRepo<TextNoteInfo, TextNoteKey, TextNoteInfo> {
    suspend fun findNotes(owner: Identifier, keywordPart: String): List<TextNoteInfo>
    suspend fun getNotes(owner: Identifier, page: Pagination): PaginationResult<TextNoteInfo>
}

private class NotesTextInlineBusPartTable(
    override val database: Database
) : NotesTextInlineBusPartRepo, AbstractExposedCRUDRepo<TextNoteInfo, TextNoteKey, TextNoteInfo>(
    tableName = "NotesTextInlineBusPartTable"
) {
    private val ownerColumn = long("owner")
    private val keywordColumn = text("keyword")
    private val textColumn = blob("text")

    override val selectByIds: SqlExpressionBuilder.(List<TextNoteKey>) -> Op<Boolean> = {
        val leftOps = it.groupBy { it.first }.map { (owner, keywords) ->
            ownerColumn.eq(owner).and(keywordColumn.inList(keywords.map { it.second }))
        }.toMutableList()
        var resultOp = leftOps.removeFirst()
        while (leftOps.isNotEmpty()) {
            resultOp = resultOp.or(leftOps.removeFirst())
        }
        resultOp
    }
    override val InsertStatement<Number>.asObject: TextNoteInfo
        get() = asObject(
            TextNoteInfo(
                get(ownerColumn),
                get(keywordColumn),
                textNotesSavingFormat.decodeFromByteArray(textSourcesSerializer, get(textColumn).bytes)
            )
        )

    override val selectById: SqlExpressionBuilder.(TextNoteKey) -> Op<Boolean> = {
        ownerColumn.eq(it.first).and(keywordColumn.eq(it.second))
    }
    override val ResultRow.asObject: TextNoteInfo
        get() = TextNoteInfo(
            get(ownerColumn),
            get(keywordColumn),
            textNotesSavingFormat.decodeFromByteArray(textSourcesSerializer, get(textColumn).bytes)
        )

    init {
        initTable()
    }

    override fun InsertStatement<Number>.asObject(value: TextNoteInfo): TextNoteInfo = value

    override fun insert(value: TextNoteInfo, it: InsertStatement<Number>) {
        it[ownerColumn] = value.owner
        it[keywordColumn] = value.keyword
        it[textColumn] = ExposedBlob(textNotesSavingFormat.encodeToByteArray(textSourcesSerializer, value.sources))
    }

    override fun update(id: TextNoteKey, value: TextNoteInfo, it: UpdateStatement) {
        it[textColumn] = ExposedBlob(textNotesSavingFormat.encodeToByteArray(textSourcesSerializer, value.sources))
    }

    override suspend fun findNotes(
        owner: Identifier,
        keywordPart: String
    ): List<TextNoteInfo> = transaction(database) {
        select { ownerColumn.eq(owner).and(keywordColumn.like("%$keywordPart%")) }.map { it.asObject }
    }

    private fun count(owner: Identifier) = transaction(database) {
        select { ownerColumn.eq(owner) }.count()
    }

    override suspend fun getNotes(owner: Identifier, page: Pagination): PaginationResult<TextNoteInfo> = transaction(database) {
        select { ownerColumn.eq(owner) }.paginate(page).map { it.asObject } to count(owner)
    }.let { (list, count) ->
        list.createPaginationResult(
            page,
            count
        )
    }
}

class NotesTextInlineBusPart(
    database: Database
) : InlineBusPluginPart {
    private val textNotesTable: NotesTextInlineBusPartRepo = NotesTextInlineBusPartTable(database)

    suspend fun saveNote(noteInfo: TextNoteInfo) {
        textNotesTable.deleteById(noteInfo.key)
        textNotesTable.create(noteInfo)
    }

    suspend fun buildButtonsForManagement(user: User, page: Int = 0) {
        val pagination = defaultNotesPage.copy(page)
        val notes = textNotesTable.getNotes(user.id.chatId, pagination)
        val chunked = notes.results.chunked(2).map {

        }
    }

    override suspend fun getResults(user: User, query: String): List<InlineQueryResult> {
        val notes = textNotesTable.findNotes(user.id.chatId, query)
        return notes.map {
            InlineQueryResultArticle(
                it.key.textNoteKey(),
                it.keyword,
                InputTextMessageContent(it.sources),
                description = it.sources.makeString().take(100)
            )
        }
    }
}

const val SaveNoteCommand = "save_as_note"
const val RemoveNoteCommand = "remove_note"
val RemoveNoteCommandFirstPage = FirstPagePagination(6)

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
            val repliedTo = commandMessage.replyTo ?.asContentMessage()
            if (repliedTo == null) {
                reply(commandMessage, "Reply to message and send me /$SaveNoteCommand to save note")
                return@command
            }
            val content = repliedTo.content
            if (content !is TextContent) {
                reply(commandMessage, "Unfortunately, currently supported only text messages")
                return@command
            }
            val user = commandMessage.asFromUserMessage() ?.user ?: let { _ ->
                reply(commandMessage, "Unfortunately, I can't recognise user from message. Send me message in private or as common user in group")
                return@command
            }
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
            textPart.saveNote(TextNoteInfo(user.id.chatId, title, content.textSources))
            reply(commandMessage, "Note saved with title $title")
        }

        return listOf(
            textPart
        )
    }
}
