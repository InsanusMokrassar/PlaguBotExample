package dev.inmo.plagubot.example

import dev.inmo.kslog.common.*
import dev.inmo.kslog.common.filter.filtered
import dev.inmo.micro_utils.coroutines.*
import dev.inmo.micro_utils.koin.singleWithBinds
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.plugins.inline.buttons.utils.enableSettings
import dev.inmo.tgbotapi.extensions.api.bot.deleteMyCommands
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.chat.members.unbanChatMember
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.*
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.retrieveAccumulatedUpdates
import dev.inmo.tgbotapi.types.chat.member.KickedChatMember
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.*
import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.koin.core.qualifier.named

@Serializable
class CustomPlugin : Plugin, KoinComponent {
    private val log = logger
    private val flushUpdates by inject<Boolean>(named("flushUpdates"))
    private val clearCommands by inject<Boolean>(named("clearCommands"))

    init {
        KSLog.default = KSLog("ExampleBot").filtered { l, t, throwable ->
            l > LogLevel.VERBOSE && throwable !is CancellationException && throwable !is HttpRequestTimeoutException
        }
    }

    override fun Module.setupDI(config: JsonObject) {
        singleWithBinds<StringFormat> { get<Json>() }
        single(named("flushUpdates")) {
            runCatching {
                config["flushUpdates"]?.jsonPrimitive ?.booleanOrNull == true
            }.getOrElse {
                false
            }
        }
        single(named("clearCommands")) {
            runCatching {
                config["clearCommands"]?.jsonPrimitive ?.booleanOrNull == true
            }.getOrElse {
                false
            }
        }
        single<Koin> {
            getKoin()
        }
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        if (flushUpdates) {
            log.i("Start flush updates")
            retrieveAccumulatedUpdates {
                log.i(it.toString())// just flush
            }.join()
            log.i("Updates flushed")
        }
        if (clearCommands) {
            log.i("Start clear commands")
            runCatchingSafely {
                deleteMyCommands()
            }.onFailure {
                log.e(it) { "Default have not been cleared" }
            }.onSuccess {
                log.i { "Default commands cleared" }
            }
        }
        allUpdatesFlow.subscribeSafelyWithoutExceptions(this) {
            log.d { it }
        }
        val currentDefaultSafelyExceptionHandler = defaultSafelyExceptionHandler
        defaultSafelyExceptionHandler = {
            if (it !is CancellationException) {
                it.printStackTrace()
            }
            currentDefaultSafelyExceptionHandler(it)
        }
        println(getMe())

        onChatMemberUpdated {
            if (it.newChatMemberState is KickedChatMember) {
                unbanChatMember(it.chat.id, it.user, onlyIfBanned = true)
            }
        }

        onCommand(Regex(".*")) {
            log.d { "Handled command: ${it.content}" }
        }
        onUnhandledCommand {
            log.v { "Unknown command: ${it.content}" }
        }
        enableSettings(
            koin.get(),
            koin.get(),
        )
    }
}
