package dev.inmo.plagubot.example

import dev.inmo.plagubot.Plugin
import dev.inmo.tgbotapi.extensions.api.answers.answerInlineQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onBaseInlineQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.utils.marker_factories.ByUserInlineQueryMarkerFactory
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.InlineQueries.InlineQueryResult.abstracts.InlineQueryResult
import dev.inmo.tgbotapi.types.chat.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.koin.core.Koin
import kotlin.math.min

interface InlineBusPluginPart {
    suspend fun getResults(user: User, query: String): List<InlineQueryResult>
}

interface InlineBusPluginPartFactory {
    val additionalCommands: List<BotCommand>
        get() = emptyList()
    suspend fun BehaviourContext.createParts(koin: Koin): List<InlineBusPluginPart>
}

@Serializable
class InlineBusPlugin : Plugin {
    @Transient
    private val maxAnswers = inlineQueryAnswerResultsLimit.last

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val factories = koin.getAll<InlineBusPluginPartFactory>().distinct()
        val parts = (factories.flatMap {
            it.run { createParts(koin) }
        } + koin.getAll<InlineBusPluginPart>()).distinct()

        onBaseInlineQuery(
            markerFactory = ByUserInlineQueryMarkerFactory
        ) {
            val results = mutableListOf<InlineQueryResult>()

            val page = it.offset.toIntOrNull() ?: 0
            var leftToDrop = page * maxAnswers

            for (part in parts) {
                val currentResults = part.getResults(it.from, it.query)
                val dropped = if (leftToDrop > 0) {
                    val willBeDropped = min(currentResults.size, leftToDrop)
                    leftToDrop -= willBeDropped
                    currentResults.drop(willBeDropped)
                } else {
                    currentResults
                }

                results.addAll(dropped.take(maxAnswers))
                if (results.size >= maxAnswers) {
                    break
                }
            }

            answerInlineQuery(
                it,
                results.take(maxAnswers),
                cachedTime = 0,
                isPersonal = true,
                nextOffset = (page + 1).toString(),
            )
        }
    }
}
