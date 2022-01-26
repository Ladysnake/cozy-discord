/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:Suppress("DataClassShouldBeImmutable", "DataClassContainsFunctions")

package org.quiltmc.community.database.entities

import com.kotlindiscord.kord.extensions.utils.getKoin
import dev.kord.common.entity.Snowflake
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.quiltmc.community.database.Entity
import org.quiltmc.community.database.collections.UserRestrictionsCollection

@Serializable
data class UserRestrictions(
    override val _id: Snowflake,
    val guildId: Snowflake,

    var isBanned: Boolean = false,
    var returningBanTime: Instant? = null,
) : Entity<Snowflake> {
    suspend fun save() {
        val collection = getKoin().get<UserRestrictionsCollection>()

        collection.set(this)
    }
}
