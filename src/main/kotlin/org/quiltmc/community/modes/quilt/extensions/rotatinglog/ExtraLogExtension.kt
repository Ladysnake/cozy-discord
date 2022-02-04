/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.rotatinglog

import com.kotlindiscord.kord.extensions.DISCORD_GREEN
import com.kotlindiscord.kord.extensions.DISCORD_RED
import com.kotlindiscord.kord.extensions.DISCORD_YELLOW
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.time.TimestampType
import com.kotlindiscord.kord.extensions.time.toDiscord
import com.kotlindiscord.kord.extensions.utils.createdAt
import dev.kord.common.entity.optional.Optional
import dev.kord.core.event.guild.GuildUpdateEvent
import dev.kord.core.event.guild.MemberJoinEvent
import dev.kord.core.event.guild.MemberLeaveEvent
import dev.kord.core.event.guild.MemberUpdateEvent
import dev.kord.rest.Image
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.koin.core.component.inject
import org.quiltmc.community.asGuild
import org.quiltmc.community.database.collections.GlobalSettingsCollection
import org.quiltmc.community.inLadysnakeGuild

class ExtraLogExtension : Extension() {
    override val name = "extra-logging"
    private val logger = KotlinLogging.logger {}

    private val messageLogExtension = bot.findExtension<MessageLogExtension>()

    private val globalSettings: GlobalSettingsCollection by inject()

    override suspend fun setup() {
        // this will just use MessageLog's rotators

        //region: Member join/leave/update

        event<MemberJoinEvent> {
            check { inLadysnakeGuild() }

            action {
                val member = event.member

                val otherServers = globalSettings.get()!!.ladysnakeGuilds
                    .filterNot { it == event.guildId }
                    .mapNotNull { it.asGuild() }
                    .filter { it.getMemberOrNull(member.id) != null }
                    .map { it.name }
                    .let { if (it.isEmpty()) "None" else it.joinToString(", ") }

                messageLogExtension?.getRotator(event.guildId)
                    ?.logOther {
                        embed {
                            title = "Member Joined"
                            color = DISCORD_GREEN

                            field {
                                name = "Identification"
                                value = """
                                    Snowflake: ${member.id}
                                    Tag: ${member.tag}
                                    Mention: ${member.mention}
                                """.trimIndent()
                                inline = true
                            }

                            field {
                                name = "Information"
                                value = """
                                    User created at: ${member.createdAt.toDiscord(TimestampType.Default)}
                                    Other Ladysnake servers: $otherServers
                                """.trimIndent()
                            }

                            thumbnail {
                                url = member.avatar?.url ?: member.defaultAvatar.url
                            }
                        }
                    }
            }
        }

        event<MemberLeaveEvent> {
            check { inLadysnakeGuild() }

            action {
                val member = event.user
                val joinTime = event.old?.joinedAt?.toDiscord(TimestampType.Default) ?: "Unknown"

                val roles = try {
                    event.old?.roles
                        ?.map { "• ${it.name}" }
                        ?.reduce { prev, value -> "$prev\n$value" }
                        ?: "Unknown"
                } catch (e: NoSuchElementException) {
                    "None" // Flow.reduce throws this if empty
                }

                messageLogExtension?.getRotator(event.guildId)
                    ?.logOther {
                        embed {
                            title = "Member Left"
                            color = DISCORD_RED

                            field {
                                name = "Identification"
                                value = """
                                    Snowflake: ${member.id}
                                    Tag: ${member.tag}
                                    Mention: ${member.mention}
                                """.trimIndent()
                                inline = true
                            }

                            field {
                                name = "Information"
                                value = """
                                    User created at: ${member.createdAt.toDiscord(TimestampType.Default)}
                                    Joined at: $joinTime
                                """.trimIndent()

                                inline = true
                            }

                            field {
                                name = "Roles"
                                value = roles

                                inline = true
                            }

                            thumbnail {
                                url = member.avatar?.url ?: member.defaultAvatar.url
                            }
                        }
                    }
            }
        }

        event<MemberUpdateEvent> {
            check { inLadysnakeGuild() }

            action {
                val member = event.member
                val old = event.old

                if (old == null) {
                    messageLogExtension?.getRotator(event.guildId)
                        ?.logOther {
                            embed {
                                title = "Member Updated"
                                description = "*This member's data was not cached, so limited information is " +
                                        "available.*"
                                color = DISCORD_YELLOW

                                field {
                                    name = "Identification"
                                    value = """
                                        Snowflake: ${member.id}
                                        Tag: ${member.tag}
                                        Mention: ${member.mention}
                                    """.trimIndent()
                                    inline = true
                                }

                                field {
                                    name = "Information"
                                    value = """
                                        User created at: ${member.createdAt.toDiscord(TimestampType.Default)}
                                    """.trimIndent()
                                }

                                thumbnail {
                                    url = member.avatar?.url ?: member.defaultAvatar.url
                                }
                            }
                        }
                } else {
                    val diff = MemberDiff(member, old)
                    if (diff.isIdentical) return@action

                    messageLogExtension?.getRotator(event.guildId)
                        ?.logOther {
                            embed {
                                title = "Member Updated"
                                description = member.mention
                                color = DISCORD_YELLOW

                                //region: User data (not member data)

                                if (diff.username is Optional.Value) {
                                    field {
                                        name = "Username"
                                        value = """
                                            Old: ${old.username}
                                            New: ${member.username}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                if (diff.discriminator is Optional.Value) {
                                    field {
                                        name = "Discriminator"
                                        value = """
                                            Old: ${old.discriminator}
                                            New: ${member.discriminator}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                if (diff.avatar is Optional.Value) {
                                    field {
                                        name = "Avatar"
                                        value = """
                                            Old: ${old.avatar?.url ?: old.defaultAvatar.url}
                                            New: ${member.avatar?.url ?: member.defaultAvatar.url}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                if (diff.publicFlags is Optional.Value) {
                                    field {
                                        name = "Public Flags"
                                        value = """
                                            Old: ${old.publicFlags}
                                            New: ${member.publicFlags}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                if (diff.banner is Optional.Value) {
                                    field {
                                        name = "Banner"
                                        value = """
                                            Old: ${old.data.banner ?: "None"}
                                            New: ${member.data.banner ?: "None"}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                if (diff.accentColor is Optional.Value) {
                                    field {
                                        name = "Accent Color"
                                        value = """
                                            Old: ${old.data.accentColor}
                                            New: ${member.data.accentColor}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                //endregion: User data (not member data)

                                //region: Member data

                                if (diff.nickname is Optional.Value) {
                                    field {
                                        name = "Nickname"
                                        value = """
                                            Old: ${old.nickname ?: "None"}
                                            New: ${member.nickname ?: "None"}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                if (diff.roles is Optional.Value) {
                                    val oldRoles = old.memberData.roles
                                    val newRoles = member.memberData.roles

                                    val addedRoles = newRoles.filter { it !in oldRoles }
                                    val removedRoles = oldRoles.filter { it !in newRoles }

                                    if (addedRoles.isNotEmpty()) {
                                        field {
                                            name = "Added Roles"
                                            value = addedRoles.joinToString(", ") { "<@&$it>" }

                                            inline = true
                                        }
                                    }

                                    if (removedRoles.isNotEmpty()) {
                                        field {
                                            name = "Removed Roles"
                                            value = removedRoles.joinToString(", ") { "<@&$it>" }

                                            inline = true
                                        }
                                    }
                                }

                                if (diff.premiumSince is Optional.Value) {
                                    field {
                                        name = "Premium Since"
                                        value = """
                                            Old: ${old.memberData.premiumSince.value ?: "None"}
                                            New: ${member.memberData.premiumSince.value ?: "None"}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                if (diff.pending is Optional.Value) {
                                    // you can't go from pending to not pending
                                    field {
                                        name = "Pending"
                                        value = "This user is no longer pending."

                                        inline = true
                                    }
                                }

                                if (diff.serverAvatar is Optional.Value) {
                                    field {
                                        name = "Server Avatar"
                                        value = """
                                            Old: ${old.memberData.avatar.value ?: "None"}
                                            New: ${member.memberData.avatar.value ?: "None"}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                if (diff.timeoutTime is Optional.Value) {
                                    field {
                                        name = "Timeout Time"
                                        value = """
                                            Old: ${old.memberData.communicationDisabledUntil.value ?: "None"}
                                            New: ${member.memberData.communicationDisabledUntil.value ?: "None"}
                                        """.trimIndent()

                                        inline = true
                                    }
                                }

                                //endregion: Member Data
                            }
                        }
                }
            }
        }

        //endregion

        //region: Server changes

        event<GuildUpdateEvent> {
            check { inLadysnakeGuild() }

            action {
                val old = event.old
                val new = event.guild

                if (old == null) return@action

                val diff = GuildDiff(new, old)

                val allWatchedFieldsAreIdentical = diff.allMissing {
                    listOf(
                        ::name,
                        ::icon,
                        ::splash,
                        ::discoverySplash,
                        ::ownerId,
                        ::permissions,
                        ::afkChannelId,
                        ::afkTimeout,
                        ::verificationLevel,
                        ::explicitContentFilter,
                        ::roles,
                        ::emojis,
                        ::features,
                        ::mfaLevel,
                        ::systemChannelId,
//                        ::systemChannelFlags,
                        ::rulesChannelId,
                        ::channels,
                        ::maxMembers,
                        ::vanityUrlCode,
                        ::description,
                        ::banner,
                        ::preferredLocale,
                        ::publicUpdatesChannelId,
                        ::welcomeScreen,
                    )
                }

                if (allWatchedFieldsAreIdentical) return@action

                messageLogExtension?.getRotator(new.id)?.logOther {
                    embed {
                        title = "Server Updated"
                        color = DISCORD_YELLOW

                        if (diff.name is Optional.Value) {
                            field {
                                name = "Name"
                                value = """
                                    Old: ${old.name}
                                    New: ${new.name}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.icon is Optional.Value) {
                            field {
                                name = "Icon"
                                value = """
                                    Old: ${old.data.icon}
                                    New: ${new.data.icon}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.splash is Optional.Value) {
                            field {
                                name = "Splash"
                                value = """
                                    Old: ${old.data.splash}
                                    New: ${new.data.splash}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.discoverySplash is Optional.Value) {
                            field {
                                name = "Discovery Splash"
                                value = """
                                    Old: ${old.data.discoverySplash}
                                    New: ${new.data.discoverySplash}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.ownerId is Optional.Value) {
                            field {
                                name = "Owner"
                                value = """
                                    Old: ${old.owner.id} (<@!${old.owner.id}>)
                                    New: ${new.owner.id} (<@!${new.owner.id}>)
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.permissions is Optional.Value) {
                            field {
                                name = "Permissions"
                                value = """
                                    Old: ${old.permissions}
                                    New: ${new.permissions}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.afkChannelId is Optional.Value) {
                            field {
                                name = "AFK Channel"
                                value = """
                                    Old: ${old.afkChannel?.id} (<#${old.afkChannel?.id}>)
                                    New: ${new.afkChannel?.id} (<#${new.afkChannel?.id}>)
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.afkTimeout is Optional.Value) {
                            field {
                                name = "AFK Timeout"
                                value = """
                                    Old: ${old.afkTimeout}
                                    New: ${new.afkTimeout}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.verificationLevel is Optional.Value) {
                            field {
                                name = "Verification Level"
                                value = """
                                    Old: ${old.verificationLevel::class.simpleName}
                                    New: ${new.verificationLevel::class.simpleName}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.explicitContentFilter is Optional.Value) {
                            field {
                                name = "Explicit Content Filter"
                                value = """
                                    Old: ${old.data.explicitContentFilter::class.simpleName}
                                    New: ${new.data.explicitContentFilter::class.simpleName}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.roles is Optional.Value) {
                            val oldRoles = old.roleIds
                            val newRoles = new.roleIds

                            val addedRoles = newRoles.filter { it !in oldRoles }
                            val removedRoles = oldRoles.filter { it !in newRoles }

                            field {
                                name = "Roles"
                                value = """
                                    Added: ${addedRoles.joinToString(", ") { "<@&$it>" }}
                                    Removed: ${removedRoles.joinToString(", ") { "<@&$it>" }}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.emojis is Optional.Value) {
                            // emojis are in flows, so we have to map then turn them into a list
                            val oldEmojis = old.emojis.map { it.mention }.toList()
                            val newEmojis = new.emojis.map { it.mention }.toList()

                            val addedEmojis = newEmojis.filter { it !in oldEmojis }
                            val removedEmojis = oldEmojis.filter { it !in newEmojis }

                            field {
                                name = "Emojis"
                                value = """
                                    Added: ${addedEmojis.joinToString(", ")}
                                    Removed: ${removedEmojis.joinToString(", ")}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.features is Optional.Value) {
                            field {
                                name = "Features"
                                value = """
                                    Old: ${old.features.joinToString(", ") { it.value }}
                                    New: ${new.features.joinToString(", ") { it.value }}
                                """.trimIndent()
                            }
                        }

                        if (diff.mfaLevel is Optional.Value) {
                            field {
                                name = "MFA Level"
                                value = """
                                    Old: ${old.mfaLevel::class.simpleName}
                                    New: ${new.mfaLevel::class.simpleName}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.systemChannelId is Optional.Value) {
                            field {
                                name = "System Channel"
                                value = """
                                    Old: ${old.systemChannelId?.let { "<#$it>" } ?: "None"}
                                    New: ${new.systemChannelId?.let { "<#$it>" } ?: "None"}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.rulesChannelId is Optional.Value) {
                            field {
                                name = "Rules Channel"
                                value = """
                                    Old: ${old.rulesChannelId?.let { "<#$it>" } ?: "None"}
                                    New: ${new.rulesChannelId?.let { "<#$it>" } ?: "None"}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.channels is Optional.Value) {
                            val oldChannels = old.channels.map { it.mention }.toList()
                            val newChannels = new.channels.map { it.mention }.toList()

                            val addedChannels = newChannels.filter { it !in oldChannels }
                            val removedChannels = oldChannels.filter { it !in newChannels }

                            field {
                                name = "Channels"
                                value = """
                                    Added: ${addedChannels.joinToString(", ")}
                                    Removed: ${removedChannels.joinToString(", ")}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.maxMembers is Optional.Value) {
                            field {
                                name = "Max Members"
                                value = """
                                    Old: ${old.maxMembers}
                                    New: ${new.maxMembers}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.vanityUrlCode is Optional.Value) {
                            field {
                                name = "Vanity URL Code"
                                value = """
                                    Old: ${old.vanityUrl ?: "None"}
                                    New: ${new.vanityUrl ?: "None"}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.description is Optional.Value) {
                            field {
                                name = "Description"
                                value = """
                                    Old: ${old.description ?: "None"}
                                    New: ${new.description ?: "None"}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.banner is Optional.Value) {
                            field {
                                name = "Banner"
                                value = """
                                    Old: ${old.getBannerUrl(Image.Format.PNG) ?: "None"}
                                    New: ${new.getBannerUrl(Image.Format.PNG) ?: "None"}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.preferredLocale is Optional.Value) {
                            field {
                                name = "Preferred Locale"
                                value = """
                                    Old: ${old.preferredLocale}
                                    New: ${new.preferredLocale}
                                """.trimIndent()
                            }
                        }

                        if (diff.publicUpdatesChannelId is Optional.Value) {
                            field {
                                name = "Public Updates Channel"
                                value = """
                                    Old: ${old.publicUpdatesChannelId?.let { "<#$it>" } ?: "None"}
                                    New: ${new.publicUpdatesChannelId?.let { "<#$it>" } ?: "None"}
                                """.trimIndent()

                                inline = true
                            }
                        }

                        if (diff.welcomeScreen is Optional.Value) {
                            field {
                                name = "Welcome Screen"
                                value = """
                                    Old: ${old.welcomeScreen?.let { "<#$it>" } ?: "None"}
                                    New: ${new.welcomeScreen?.let { "<#$it>" } ?: "None"}
                                """.trimIndent()

                                inline = true
                            }
                        }
                    }
                }
            }
        }

        //endregion
    }
}
