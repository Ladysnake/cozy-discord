/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:Suppress("DataClassShouldBeImmutable")  // Well, yes, but actually no.

package org.quiltmc.community.database.entities

import dev.kord.common.entity.Snowflake
import kotlinx.serialization.Serializable
import org.quiltmc.community.database.Entity

@Serializable
data class PingGroup(
	override val _id: String,
	val name: String,
	val guildId: Snowflake,
	var canSelfSubscribe: Boolean = false,
	val emoji: String? = null,
	val desc: String? = null,
	val users: MutableSet<Snowflake> = mutableSetOf(),
) : Entity<String>
