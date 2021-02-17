package dev.inmo.plagubot.example

import dev.inmo.micro_utils.coroutines.subscribeSafelyWithoutExceptions
import dev.inmo.plagubot.Plugin
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database

@Serializable
class CustomPlugin : Plugin {
    override suspend fun BehaviourContext.invoke(database: Database, params: Map<String, Any>) {
        allUpdatesFlow.subscribeSafelyWithoutExceptions(this) {
            println(it)
        }
    }
}