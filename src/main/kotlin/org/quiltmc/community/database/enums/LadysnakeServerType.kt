package org.quiltmc.community.database.enums

import com.kotlindiscord.kord.extensions.commands.application.slash.converters.ChoiceEnum
import kotlinx.serialization.Serializable

@Serializable
enum class LadysnakeServerType(override val readableName: String) : ChoiceEnum {
    LADYSNAKE("Ladysnake"),
    YOUTUBE("Rat's YouTube"),
}
