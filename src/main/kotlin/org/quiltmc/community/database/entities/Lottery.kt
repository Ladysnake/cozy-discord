package org.quiltmc.community.database.entities

import dev.kord.common.entity.Snowflake
import kotlinx.serialization.Serializable
import org.quiltmc.community.database.Entity

@Serializable
data class Lottery(
    override val _id: Snowflake,

    val participants: MutableSet<Snowflake>,
    val winners: Int,
) : Entity<Snowflake>
