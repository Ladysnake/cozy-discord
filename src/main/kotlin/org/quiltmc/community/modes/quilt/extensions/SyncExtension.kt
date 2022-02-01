/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:Suppress("StringLiteralDuplication")

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.checks.hasPermission
import com.kotlindiscord.kord.extensions.checks.types.CheckContext
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.chatGroupCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.utils.hasPermission
import com.kotlindiscord.kord.extensions.utils.respond
import com.kotlindiscord.kord.extensions.utils.translate
import dev.kord.common.entity.AuditLogEvent
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.ban
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.channel.withTyping
import dev.kord.core.behavior.getAuditLogEntries
import dev.kord.core.entity.Guild
import dev.kord.core.event.Event
import dev.kord.core.event.guild.BanAddEvent
import dev.kord.core.event.guild.BanRemoveEvent
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.quiltmc.community.GUILDS
import org.quiltmc.community.asUser
import org.quiltmc.community.getModLogChannel
import org.quiltmc.community.inLadysnakeGuild

private val BAN_PERMS: Array<Permission> = arrayOf(Permission.BanMembers, Permission.Administrator)
private val ROLE_PERMS: Array<Permission> = arrayOf(Permission.ManageRoles, Permission.Administrator)

class SyncExtension : Extension() {
    override val name: String = "sync"

    private val logger = KotlinLogging.logger {}

    private suspend fun <T : Event> CheckContext<T>.hasBanPerms() {
        fail(
            "Must have at least one of these permissions: " + BAN_PERMS.joinToString {
                "**${it.translate(locale)}**"
            }
        )

        BAN_PERMS.forEach {
            val innerCheck = CheckContext(event, locale)
            innerCheck.hasPermission(it)

            if (innerCheck.passed) {
                pass()

                return
            }
        }
    }

    private suspend fun <T : Event> CheckContext<T>.hasBanOrRolePerms() {
        val requiredPerms = (BAN_PERMS + ROLE_PERMS).toSet()

        fail(
            "Must have at least one of these permissions: " + requiredPerms.joinToString { perm ->
                "**${perm.translate(locale)}**"
            }
        )

        requiredPerms.forEach {
            val innerCheck = CheckContext(event, locale)
            innerCheck.hasPermission(it)

            if (innerCheck.passed) {
                pass()

                return
            }
        }
    }

    @Suppress("SpreadOperator")  // No better way atm, and performance impact is negligible
    override suspend fun setup() {
        chatGroupCommand {
            name = "sync"
            description = "Synchronisation commands."

            check { inLadysnakeGuild() }
            check { hasBanOrRolePerms() }

            chatCommand {
                name = "bans"
                description = "Additively synchronise bans between all servers, so that everything matches."

                check { inLadysnakeGuild() }
                check { hasBanPerms() }

                requireBotPermissions(Permission.BanMembers)

                action {
                    val guilds = getGuilds()

                    logger.info { "Syncing bans for ${guilds.size} guilds." }

                    guilds.forEach {
                        logger.debug { "${it.id.value} -> ${it.name}" }

                        val member = it.getMember(this@SyncExtension.kord.selfId)

                        if (!BAN_PERMS.any { perm -> member.hasPermission(perm) }) {
                            message.respond(
                                "I don't have permission to ban members on ${it.name} (`${it.id.value}`)"
                            )

                            return@action
                        }
                    }

                    val allBans: MutableMap<Snowflake, String?> = mutableMapOf()
                    val syncedBans: MutableMap<Guild, Int> = mutableMapOf()

                    message.channel.withTyping {
                        guilds.forEach { guild ->
                            guild.bans.toList().forEach { ban ->
                                if (allBans[ban.userId] == null || ban.reason?.startsWith("Synced:") == false) {
                                    // If it's null/not present or the given ban entry doesn't start with "Synced:"
                                    allBans[ban.userId] = ban.reason
                                }
                            }
                        }

                        guilds.forEach { guild ->
                            val newBans = mutableListOf<Pair<Snowflake, String>>()

                            allBans.forEach { (userId, reason) ->
                                if (guild.getBanOrNull(userId) == null) {
                                    syncedBans[guild] = (syncedBans[guild] ?: 0) + 1

                                    val newReason = "Synced: ${reason ?: "No reason given"}"

                                    guild.ban(userId) {
                                        this.reason = newReason
                                    }

                                    newBans.add(userId to newReason)
                                }
                            }

                            guild.getModLogChannel()?.createEmbed {
                                title = "Synced bans"
                                description = "**Added bans:**\n" + newBans.joinToString("\n") { (id, reason) ->
                                    "`$id` (<@!$id>) - $reason"
                                }
                            }
                        }

                        message.respond {
                            embed {
                                title = "Bans synced"

                                description = syncedBans.map { "**${it.key.name}**: ${it.value} added" }
                                    .joinToString("\n")
                            }
                        }
                    }
                }
            }
        }

        event<BanAddEvent> {
            check { inLadysnakeGuild() }

            action {
                val guilds = getGuilds().filter { it.id != event.guildId }
                val ban = event.getBan()

                guilds.forEach { guild ->
                    if (guild.getBanOrNull(ban.userId) == null) {
                        guild.ban(ban.userId) {
                            this.reason = "Synced: " + (ban.reason ?: "No reason given")
                        }

                        guild.getModLogChannel()?.createEmbed {
                            title = "Synced ban"

                            field {
                                name = "User"
                                value = """
                                    |Snowflake: ${ban.userId}
                                    |Mention: <@!${ban.userId}>
                                    |Name + discriminator: ${ban.user.asUserOrNull()?.tag ?: "Unknown"}
                                """.trimMargin()
                                inline = true
                            }

                            field {
                                name = "Reason"
                                value = ban.reason ?: "No reason given"
                            }

                            if (guild.getMember(kord.selfId).hasPermission(Permission.ViewAuditLog)) {
                                val actingModerator = guild.getAuditLogEntries {
                                    action = AuditLogEvent.MemberBanAdd
                                }.first { it.targetId == ban.userId }.userId.asUser()

                                field {
                                    name = "Responsible moderator"
                                    value = if (actingModerator != null) {
                                        "${actingModerator.mention} (${actingModerator.tag})"
                                    } else {
                                        "Unknown (see audit log)"
                                    }
                                    inline = true
                                }
                            } else {
                                field {
                                    name = "Responsible moderator"
                                    value = "*No audit log permission, the moderator cannot be identified*"
                                    inline = true
                                }
                            }
                        }
                    }
                }
            }
        }

        event<BanRemoveEvent> {
            check { inLadysnakeGuild() }

            action {
                val guilds = getGuilds().filter { it.id != event.guildId }

                guilds.forEach { guild ->
                    if (guild.getBanOrNull(event.user.id) != null) {
                        guild.unban(event.user.id)

                        guild.getModLogChannel()?.createEmbed {
                            title = "Synced unban"

                            field {
                                name = "User"
                                value = """
                                    |Snowflake: ${event.user.id}
                                    |Mention: <@!${event.user.id}>
                                    |Name + discriminator: ${event.user.tag}
                                """.trimMargin()
                                inline = true
                            }

                            if (guild.getMember(kord.selfId).hasPermission(Permission.ViewAuditLog)) {
                                val actingModerator = guild.getAuditLogEntries {
                                    action = AuditLogEvent.MemberBanRemove
                                }.first { it.targetId == event.user.id }.userId.asUser()

                                field {
                                    name = "Responsible moderator"
                                    value = if (actingModerator != null) {
                                        "${actingModerator.mention} (${actingModerator.tag})"
                                    } else {
                                        "Unknown (see audit log)"
                                    }
                                    inline = true
                                }
                            } else {
                                field {
                                    name = "Responsible moderator"
                                    value = "*No audit log permission, the moderator cannot be identified*"
                                    inline = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun getGuilds() = GUILDS.mapNotNull { kord.getGuild(it) }
}
