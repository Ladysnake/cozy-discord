/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:Suppress("DataClassShouldBeImmutable", "DataClassContainsFunctions")  // Well, yes, but actually no.

package org.quiltmc.community.database.entities

import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.utils.getKoin
import dev.kord.common.entity.ArchiveDuration
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.entity.channel.Category
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.rest.builder.message.EmbedBuilder
import kotlinx.serialization.Serializable
import org.quiltmc.community.*
import org.quiltmc.community.database.Entity
import org.quiltmc.community.database.collections.ServerSettingsCollection
import org.quiltmc.community.database.enums.LadysnakeServerType

@Serializable
@Suppress("ConstructorParameterNaming")  // MongoDB calls it that...
data class ServerSettings(
	override val _id: Snowflake,

	var commandPrefix: String? = "?",
	val moderatorRoles: MutableSet<Snowflake> = mutableSetOf(),
	var verificationRole: Snowflake? = null,

	var cozyLogChannel: Snowflake? = null,
	var filterLogChannel: Snowflake? = null,
	var messageLogCategory: Snowflake? = null,
	var moderationLogChannel: Snowflake? = null,
	var applicationLogChannel: Snowflake? = null,
	var applicationThreadsChannel: Snowflake? = null,

	var ladysnakeServerType: LadysnakeServerType? = null,
	var leaveServer: Boolean = false,
	val threadOnlyChannels: MutableSet<Snowflake> = mutableSetOf(),
	var defaultThreadLength: ArchiveDuration? = null,

	val pingTimeoutBlacklist: MutableSet<Snowflake> = mutableSetOf(),

	val exemptUsers: MutableSet<Snowflake> = mutableSetOf(),
	val exemptRoles: MutableSet<Snowflake> = mutableSetOf(),

	var vcMuteRole: Snowflake? = null,
) : Entity<Snowflake> {
	suspend fun save() {
		val collection = getKoin().get<ServerSettingsCollection>()

		collection.set(this)
	}

	suspend fun getConfiguredLogChannel(): TopGuildMessageChannel? {
		cozyLogChannel ?: return null

		val kord = getKoin().get<Kord>()
		val guild = kord.getGuildIgnoring403(_id)

		return guild?.getChannelOfOrNull(cozyLogChannel!!)
	}

	suspend fun getConfiguredMessageLogCategory(): Category? {
		messageLogCategory ?: return null

		val kord = getKoin().get<Kord>()
		val guild = kord.getGuildIgnoring403(_id)

		return guild?.getChannelOfOrNull(messageLogCategory!!)
	}

	suspend fun apply(embedBuilder: EmbedBuilder, showQuiltSettings: Boolean) {
		val kord = getKoin().get<Kord>()
		val builder = StringBuilder()
		val guild = kord.getGuildIgnoring403(_id)

		if (guild != null) {
			builder.append("Guild ID:".bold() + ' ')
            builder.append(guild.id.toString().code())
            builder.append('\n')
		}

		builder.append("Command prefix:".bold() + ' ')
        builder.append(commandPrefix)
        builder.append("\n\n")

        if (showQuiltSettings) {
			builder.append("**Application Logs:** ")

			if (applicationLogChannel != null) {
				builder.append("<#$applicationLogChannel>")
			} else {
				builder.append(":x: Not configured")
			}

			builder.append("**Application threads channel:** ")

			if (applicationThreadsChannel != null) {
				builder.append("<#$applicationThreadsChannel>")
			} else {
				builder.append(":x: Not configured")
			}
		}

		builder.append("\n")
		builder.append("Cozy Logs:".bold() + ' ')

		if (cozyLogChannel != null) {
			builder.append("<#$cozyLogChannel>")
		} else {
			builder.append(":x: Not configured")
		}

		if (showQuiltSettings) {
			builder.append("\n")
			builder.append("Filter Logs:".bold() + ' ')

			if (filterLogChannel != null) {
				builder.append("<#$filterLogChannel>")
			} else {
				builder.append(":x: Not configured")
			}

			builder.append("\n")
			builder.append("**Moderation Logs:** ")

			if (moderationLogChannel != null) {
				builder.append("<#$moderationLogChannel>")
			} else {
				builder.append(":x: Not configured")
			}
		}

		builder.append("\n")
		builder.append("Message Logs:".bold() + ' ')

		if (messageLogCategory != null) {
			builder.append("<#$messageLogCategory>")
		} else {
			builder.append(":x: Not configured")
		}

		builder.append("\n\n")

		if (showQuiltSettings) {
			builder.append("LadySnake Server Type:".bold() + ' ')

			if (ladysnakeServerType != null) {
				builder.append(ladysnakeServerType!!.readableName)
			} else {
				builder.append(":x: Not configured")
			}

			builder.append("\n")
		}

		builder.append("Leave Server Automatically:".bold() + ' ')

		if (leaveServer) {
			builder.append("Yes")
		} else {
			builder.append("No")
		}

		builder.append("\n")

		builder.append("**Verification role:** ")

		if (verificationRole != null) {
			builder.append("<@&$verificationRole>")
		} else {
			builder.append("N/A")
		}

		builder.append("\n\n")
		builder.append("Moderator Roles:".bold().underline() + '\n')

		if (moderatorRoles.isNotEmpty()) {
			moderatorRoles.forEach {
				val role = guild?.getRoleOrNull(it)

				if (role != null) {
					builder.append("**»** ${role.name.bold()} (${it.stringCode()})\n")
				} else {
					builder.append("**»** ${it.stringCode()}\n")
				}
			}
		} else {
			builder.append(":x: No roles configured")
		}

		builder.append("\n\n")
        builder.append("Thread Only Channels:".bold().underline() + '\n')

        if (threadOnlyChannels.isNotEmpty()) {
            threadOnlyChannels.forEach {
                val channel = guild?.getChannelOfOrNull<GuildMessageChannel>(it)

                if (channel != null) {
                    builder.append("**»** ${channel.name.bold()} (`${it.stringCode()}`)\n")
                } else {
                    builder.append("**»** `${it.stringCode()}`\n")
                }
            }
        } else {
            builder.append(":x: No channels configured")
        }

        builder.append("\n\n")
        builder.append("Default Thread Length:".bold() + ' ')

        if (defaultThreadLength != null) {
            val readableName = when (val length = defaultThreadLength!!) {
                is ArchiveDuration.Unknown -> "Unknown (${length.duration} minutes)"
                ArchiveDuration.Hour -> "1 hour"
                ArchiveDuration.Day -> "1 day"
                ArchiveDuration.ThreeDays -> "3 days"
                ArchiveDuration.Week -> "1 week"
            }
            builder.append(readableName)
        } else {
            builder.append(":x: Not configured (using longest server / channel setting)")
        }

        with(embedBuilder) {
            color = DISCORD_BLURPLE
            description = builder.toString()
            title = "Settings"

			title += if (guild != null) {
                ": ${guild.name}"
            } else {
                " (${_id.value})"
			}
		}
	}
}
