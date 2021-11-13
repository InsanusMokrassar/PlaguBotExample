package dev.inmo.plagubot.example

import dev.inmo.plagubot.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.formatting.buildEntities
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.InlineQueries.InlineQueryResult.InlineQueryResultArticle
import dev.inmo.tgbotapi.types.InlineQueries.InlineQueryResult.abstracts.InlineQueryResult
import dev.inmo.tgbotapi.types.InlineQueries.InputMessageContent.InputTextMessageContent
import dev.inmo.tgbotapi.types.MessageEntity.textsources.botCommand
import dev.inmo.tgbotapi.types.User
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database

data class InlineCommandsHelperBusPluginPart(
    private val commandsGetter: suspend () -> List<BotCommand>
) : InlineBusPluginPart {
    override suspend fun getResults(user: User, query: String): List<InlineQueryResult> = commandsGetter().mapIndexed { i, it ->
        InlineQueryResultArticle(
            "commands_helper_$i",
            it.command,
            InputTextMessageContent(
                buildEntities {
                    botCommand(it.command)
                }
            ),
            description = it.description
        )
    }
}
@Serializable
data class InlineCommandsHelperPluginFactory(
    @Contextual
    private val holder: PluginsHolder? = null
) : InlineBusPluginPartFactory {
    override suspend fun BehaviourContext.createParts(
        database: Database,
        params: Map<String, Any>
    ): List<InlineBusPluginPart> = listOfNotNull(
        (params.plagubot ?: holder) ?.let {
            InlineCommandsHelperBusPluginPart(it::getCommands)
        }
    )
}