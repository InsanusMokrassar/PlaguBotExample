package dev.inmo.plagubot.example

import dev.inmo.tgbotapi.types.chat.User

val User.name
    get() = listOfNotNull(
        lastName.takeIf { it.isNotBlank() }, firstName.takeIf { it.isNotBlank() }
    ).takeIf {
        it.isNotEmpty()
    } ?.joinToString(" ") ?: "User"
