/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:UseSerializers(UUIDSerializer::class)

@file:Suppress("DataClassShouldBeImmutable", "DataClassContainsFunctions")  // Well, yes, but actually no.

package org.quiltmc.community.database.entities

import com.github.jershell.kbson.UUIDSerializer
import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.utils.getKoin
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.rest.builder.message.EmbedBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.quiltmc.community.*
import org.quiltmc.community.database.Entity
import org.quiltmc.community.database.collections.GlobalSettingsCollection
import org.quiltmc.community.modes.quilt.extensions.suggestions.AutoRemoval
import org.quiltmc.community.modes.quilt.extensions.suggestions.defaultAutoRemovals
import java.util.*

@Serializable
@Suppress("ConstructorParameterNaming")  // MongoDB calls it that...
data class GlobalSettings(
	override val _id: UUID = UUID.randomUUID(),

	var appealsInvite: String? = null,
	var githubToken: String? = null,

	val ladysnakeGuilds: MutableSet<Snowflake> = mutableSetOf(
		LADYSNAKE_GUILD,
//        YOUTUBE_GUILD,
	),

	var suggestionChannel: Snowflake? = LADYSNAKE_SUGGESTION_CHANNEL,
	var suggestionChannels: MutableSet<Snowflake> = mutableSetOf(
        LADYSNAKE_SUGGESTION_CHANNEL
	),
	var githubLogChannel: Snowflake? = GITHUB_LOG_CHANNEL,

	var suggestionAutoRemovals: MutableList<AutoRemoval> = defaultAutoRemovals.toMutableList(),
) : Entity<UUID> {
	suspend fun save() {
		val collection = getKoin().get<GlobalSettingsCollection>()

		collection.set(this)
	}

	suspend fun apply(embedBuilder: EmbedBuilder) {
		val kord = getKoin().get<Kord>()
		val builder = StringBuilder()

		builder.append("Appeals Invite:".bold() + ' ')

		if (appealsInvite != null) {
			builder.append("https://discord.gg/$appealsInvite")
		} else {
			builder.append(":x: Not configured")
		}

		builder.append("\n")
		builder.append("GitHub Token:".bold() + ' ')

		if (githubToken != null) {
			builder.append("Configured")
		} else {
			builder.append(":x: Not configured")
		}

		builder.append("\n\n")
		builder.append("Suggestion Channels:".bold() + '\n')

		builder.append(
			suggestionChannels.joinToString("\n") { "**»** <#$it>" }
		)

		builder.append("\n")
		builder.append("/github Log Channel:".bold() + ' ')

		if (githubLogChannel != null) {
			builder.append("<#${githubLogChannel!!.value}>")
		} else {
			builder.append(":x: Not configured")
		}

		builder.append("\n\n")
		builder.append("Ladysnake Servers".bold().underline() + '\n')

		if (ladysnakeGuilds.isNotEmpty()) {
			ladysnakeGuilds.forEach {
				val guild = kord.getGuildIgnoring403(it)

				if (guild == null) {
					builder.append("**»** ${it.stringCode()}\n")
				} else {
					builder.append("**»** ${guild.name} (${it.stringCode()})\n")
				}
			}
		} else {
			builder.append(":x: No servers configured")
		}

		builder.append("\n\n")

        builder.append("Suggestion Auto Removals:".bold() + '\n')

        suggestionAutoRemovals.forEach {
            builder.append("**»** $it\n")
        }

        with(embedBuilder) {
            title = "Global Settings"
            color = DISCORD_BLURPLE

			description = builder.toString()
		}
	}
}
