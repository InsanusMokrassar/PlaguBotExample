package dev.inmo.plagubot.example

import dev.inmo.micro_utils.coroutines.*
import dev.inmo.plagubot.Plugin
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.chat.members.unbanChatMember
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onChatMemberUpdated
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.new_chat_member
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.retrieveAccumulatedUpdates
import dev.inmo.tgbotapi.types.chat.member.BannedChatMember
import dev.inmo.tgbotapi.types.chat.member.KickedChatMember
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.Database
import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.koin.core.qualifier.named

@Serializable
class CustomPlugin : Plugin, KoinComponent {
    private val flushUpdates by inject<Boolean>(named("flushUpdates"))

    override fun Module.setupDI(database: Database, params: JsonObject) {
        single(named("flushUpdates")) {
            runCatching {
                params["flushUpdates"]?.jsonPrimitive ?.booleanOrNull == true
            }.getOrElse {
                false
            }
        }
    }
    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
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

        onChatMemberUpdated {
            if (it.newChatMemberState is KickedChatMember) {
                unbanChatMember(it.chat.id, it.user, onlyIfBanned = true)
            }
        }
    }
}
