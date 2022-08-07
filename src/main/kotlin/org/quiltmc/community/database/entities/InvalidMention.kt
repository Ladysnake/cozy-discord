/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:Suppress("DataClassShouldBeImmutable", "DataClassContainsFunctions")

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

	val exceptions: List<Snowflake> = mutableListOf()
) : Entity<Snowflake> {
	fun addException(id: Snowflake) {
        (exceptions as MutableList<Snowflake>).add(id)
	}

	fun removeException(id: Snowflake) {
        (exceptions as MutableList<Snowflake>).remove(id)
	}

	enum class Type {
        USER,
        ROLE,
        EVERYONE,
	}
}
