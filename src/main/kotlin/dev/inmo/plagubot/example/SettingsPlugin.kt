package dev.inmo.plagubot.example

import dev.inmo.plagubot.*
import dev.inmo.plagubot.config.database
import dev.inmo.tgbotapi.extensions.api.chat.get.getChat
import dev.inmo.tgbotapi.extensions.api.edit.ReplyMarkup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.migrate_from_chat_id
import dev.inmo.tgbotapi.extensions.utils.types.buttons.InlineKeyboardRowBuilder
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageIdentifier
import dev.inmo.tgbotapi.types.UserId
import dev.inmo.tgbotapi.types.chat.GroupChatImpl
import dev.inmo.tgbotapi.types.chat.abstracts.Chat
import dev.inmo.tgbotapi.types.chat.abstracts.GroupChat
import dev.inmo.tgbotapi.types.chat.asChatType
import dev.inmo.tgbotapi.types.message.CommonGroupContentMessageImpl
import dev.inmo.tgbotapi.types.message.abstracts.AnonymousGroupContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.CommonGroupContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.GroupContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.utils.PreviewFeature
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

    private fun InlineKeyboardRowBuilder.providerDataButton(chatId: ChatId, provider: SettingsProvider) = dataButton(provider.name, "${chatId.chatId} ${provider.id}")
    public fun extractChatIdAndProviderId(data: String): Pair<ChatId, SettingsProvider> {
        val (chatIdString, providerIdString) = data.split(" ")
        val chatId = ChatId(chatIdString.toLong())
        val provider = providersMap.getValue(providerIdString)
        return chatId to provider
    }
    private fun createProvidersInlineKeyboard(chatId: ChatId) = inlineKeyboard {
        providersMap.values.chunked(4).forEach {
            row {
                it.forEach { provider ->
                    providerDataButton(chatId, provider)
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
            with (provider){
                drawSettings(chatId, it.user.id, it.message.messageId)
            }
        }
        //Collecting and forwarding chat id and data

    }
}

val Map<String, Any>.settingsPlugin
    get() = get("settingsPlugin") as? SettingsPlugin




