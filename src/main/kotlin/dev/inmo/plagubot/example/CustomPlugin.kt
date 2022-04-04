package dev.inmo.plagubot.example

import dev.inmo.micro_utils.coroutines.*
import dev.inmo.plagubot.Plugin
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.retrieveAccumulatedUpdates
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database

@Serializable
class CustomPlugin(
    private val flushUpdates: Boolean = false
) : Plugin {
    override suspend fun BehaviourContext.invoke(database: Database, params: Map<String, Any>) {
        if (flushUpdates) {
            println("Start flush updates")
            retrieveAccumulatedUpdates {
                println(it)// just flush
            }.join()
            println("Updates flushed")
        }
        allUpdatesFlow.subscribeSafelyWithoutExceptions(this) {
            println(it)
        }
        val currentDefaultSafelyExceptionHandler = defaultSafelyExceptionHandler
        defaultSafelyExceptionHandler = {
            it.printStackTrace()
            currentDefaultSafelyExceptionHandler(it)
        }
        println(getMe())
    }
}
