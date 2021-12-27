@file:Suppress("DataClassShouldBeImmutable")

package org.quiltmc.community.database.entities

import dev.kord.common.entity.Snowflake
import kotlinx.serialization.Serializable
import org.quiltmc.community.database.Entity

@Serializable
data class InvalidMention(
    override val _id: Snowflake,
    val type: Type,

    var allowsDirectMentions: Boolean = false,
    var allowsReplyMentions: Boolean = false,
) : Entity<Snowflake> {
    enum class Type {
        USER,
        ROLE,
        EVERYONE,
    }
}
