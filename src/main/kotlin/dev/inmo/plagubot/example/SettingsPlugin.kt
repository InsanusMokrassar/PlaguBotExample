package dev.inmo.plagubot.example

import dev.inmo.plagubot.*
import dev.inmo.plagubot.config.database
import dev.inmo.plagubot.example.utils.extractChatIdAndData
import dev.inmo.plagubot.example.utils.settingsDataButton
import dev.inmo.tgbotapi.extensions.api.edit.ReplyMarkup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageIdentifier
import dev.inmo.tgbotapi.types.UserId
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database

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

    override suspend fun BehaviourContext.invoke(database: Database, params: Map<String, Any>) {
        val adminsApi = params.adminsPlugin ?.adminsAPI(params.database ?: return) ?: return
        onCommand("settings") { commandMessage ->
            commandMessage.doAfterVerification(adminsApi){
                commandMessage.whenFromUser {
                    sendTextMessage(
                        it.user.id,
                        "Settings",
                        replyMarkup = createProvidersInlineKeyboard(commandMessage.chat.id)
                    )
                }
            }
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
        //Collecting and forwarding chat id and data

    }
}

val Map<String, Any>.settingsPlugin
    get() = get("settingsPlugin") as? SettingsPlugin




