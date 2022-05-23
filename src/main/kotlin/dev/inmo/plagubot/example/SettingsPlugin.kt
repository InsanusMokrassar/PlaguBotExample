package dev.inmo.plagubot.example

import dev.inmo.plagubot.*
import dev.inmo.plagubot.config.database
import dev.inmo.plagubot.example.utils.extractChatIdAndData
import dev.inmo.plagubot.example.utils.settingsDataButton
import dev.inmo.tgbotapi.extensions.api.chat.get.getChat
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.formatting.*
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageIdentifier
import dev.inmo.tgbotapi.types.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.scope.Scope

interface SettingsProvider{
    val name: String
    val id: String
    suspend fun BehaviourContext.drawSettings(chatId: ChatId, userId: UserId, messageId: MessageIdentifier)
}

@Serializable
class SettingsPlugin : SettingsProvider, Plugin{
    override val id: String = "settings"
    override val name: String = "Settings"
    private val providersMap = mutableMapOf<String, SettingsProvider>()

    fun register(provider: SettingsProvider){
        providersMap[provider.id] = provider
    }

    private fun extractChatIdAndProviderId(data: String): Pair<ChatId, SettingsProvider?> {
        val (chatId, providerId) = extractChatIdAndData(data)
        val provider = providersMap[providerId]
        return chatId to provider
    }
    private fun createProvidersInlineKeyboard(chatId: ChatId) = inlineKeyboard {
        providersMap.values.chunked(4).forEach {
            row {
                it.forEach { provider ->
                    settingsDataButton(provider.name, chatId, provider.id)
                }
            }
        }
    }

    override suspend fun BehaviourContext.drawSettings(chatId: ChatId, userId: UserId, messageId: MessageIdentifier){
        println(" Test $chatId $userId ")
        editMessageReplyMarkup(chatId, messageId, replyMarkup = createProvidersInlineKeyboard(chatId))
    }

    override fun Module.setupDI(database: Database, params: JsonObject) {
        single { this@SettingsPlugin }
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val adminsApi = koin.adminsPlugin ?.adminsAPI(koin.get()) ?: return
        onCommand("settings") { commandMessage ->
            val verified = commandMessage.doAfterVerification(adminsApi) {
                commandMessage.whenFromUser {
                    runCatching {
                        sendTextMessage(
                            it.user.id,
                            buildEntities {
                                +"Settings for chat "
                                code(commandMessage.chat.requireGroupChat().title)
                            },
                            replyMarkup = createProvidersInlineKeyboard(commandMessage.chat.id)
                        )
                    }.onFailure {
                        it.printStackTrace()
                        reply(
                            commandMessage,
                            "Looks like you didn't started the bot. Please start bot and try again"
                        )
                    }
                }
                true
            }
            if (verified == true) {
                return@onCommand
            }
            reply(commandMessage, "Only admins may trigger settings")
        }
        onMessageDataCallbackQuery{
            val (chatId, provider) = extractChatIdAndProviderId(it.data)
            if (provider == null) {
                return@onMessageDataCallbackQuery
            }
            with (provider){
                drawSettings(chatId, it.user.id, it.message.messageId)
            }
        }
    }
}

val Scope.settingsPlugin
    get() = getOrNull<SettingsPlugin>()
val Koin.settingsPlugin
    get() = getOrNull<SettingsPlugin>()




