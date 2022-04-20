/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:Suppress("StringLiteralDuplication")

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.checks.hasPermission
import com.kotlindiscord.kord.extensions.checks.types.CheckContext
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.sentry.BreadcrumbType
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.hasPermission
import com.kotlindiscord.kord.extensions.utils.selfMember
import com.kotlindiscord.kord.extensions.utils.timeoutUntil
import com.kotlindiscord.kord.extensions.utils.translate
import dev.kord.common.entity.AuditLogEvent
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.ban
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.getAuditLogEntries
import dev.kord.core.entity.Guild
import dev.kord.core.event.Event
import dev.kord.core.event.guild.BanAddEvent
import dev.kord.core.event.guild.BanRemoveEvent
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.quiltmc.community.GUILDS
import org.quiltmc.community.asUser
import org.quiltmc.community.getModLogChannel
import org.quiltmc.community.inLadysnakeGuild
import org.quiltmc.community.modes.quilt.extensions.moderation.ModerationExtension

private val BAN_PERMS: Array<Permission> = arrayOf(Permission.BanMembers, Permission.Administrator)
private val TIMEOUT_PERMS: Array<Permission> = arrayOf(Permission.ModerateMembers, Permission.Administrator)
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
        ephemeralSlashCommand {
            name = "sync"
            description = "Synchronisation commands."

            check { inLadysnakeGuild() }
            check { hasBanOrRolePerms() }

            ephemeralSubCommand {
                name = "bans"
                description = "Additively sync bans between all servers, so that everything matches."

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
                            respond {
                                content = "I don't have permission to ban members on ${it.name} (`${it.id.value}`)"
                            }

                            return@action
                        }
                    }

                    val allBans: MutableMap<Snowflake, String?> = mutableMapOf()
                    val syncedBans: MutableMap<Guild, Int> = mutableMapOf()

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

                    respond {
                        embed {
                            title = "Bans synced"

                            description = syncedBans.map { "**${it.key.name}**: ${it.value} added" }
                                .joinToString("\n")
                        }
                    }
                }
            }

            ephemeralSubCommand {
                name = "timeouts"
                description = "Additively sync timeouts between all servers, so that everything matches."

                check { inLadysnakeGuild() }
                check { hasBanPerms() }

                requireBotPermissions(Permission.ModerateMembers)

                action {
                    val guilds = getGuilds()

                    sentry.breadcrumb(BreadcrumbType.Info) {
                        message = "Syncing timeouts for ${guilds.size} guilds."
                    }

                    logger.info { "Syncing timeouts for ${guilds.size} guilds." }

                    guilds.forEach {
                        logger.debug { "${it.id.value} -> ${it.name}" }

                        val member = it.getMember(this@SyncExtension.kord.selfId)

                        if (!TIMEOUT_PERMS.any { perm -> member.hasPermission(perm) }) {
                            respond {
                                content = "I don't have permission to timeout members on ${it.name} (`${it.id.value}`)"
                            }

                            return@action
                        }
                    }

                    sentry.breadcrumb(BreadcrumbType.Info) {
                        message = "Ensured that the bot has adequate permissions on all servers."
                    }

                    val allTimeouts: MutableMap<Snowflake, Instant> = mutableMapOf()
                    val syncedTimeouts: MutableMap<Guild, Int> = mutableMapOf()

                    guilds.forEach { guild ->
                        sentry.breadcrumb(BreadcrumbType.Info) {
                            message = "Collecting timed-out members for guild: ${guild.name} (${guild.id})"
                        }

                        guild.members
                            .filter { it.timeoutUntil != null }
                            .collect {
                                val current = allTimeouts[it.id]

                                if (current == null || current < it.timeoutUntil!!) {
                                    allTimeouts[it.id] = it.timeoutUntil!!
                                }
                            }
                    }

                    sentry.breadcrumb(BreadcrumbType.Info) {
                        message = "Collected ${allTimeouts.size} timeouts."
                    }

                    guilds.forEach { guild ->
                        sentry.breadcrumb(BreadcrumbType.Info) {
                            message = "Applying up to ${allTimeouts.size} timeouts for guild: ${guild.name} " +
                                    "(${guild.id})"
                        }

                        for ((userId, expiry) in allTimeouts) {
                            val member = guild.getMemberOrNull(userId) ?: continue

                            if (member.timeoutUntil != expiry) {
                                member.edit {
                                    timeoutUntil = expiry

                                    reason = "Synced automatically"
                                }

                                syncedTimeouts[guild] = (syncedTimeouts[guild] ?: 0) + 1
                            }
                        }
                    }

                    respond {
                        embed {
                            title = "Timeouts synced"

                            description = syncedTimeouts.map { "**${it.key.name}**: ${it.value} added" }
                                .joinToString("\n")
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

                            val canSeeAuditLog = guild.selfMember().hasPermission(Permission.ViewAuditLog) ||
                                    guild.selfMember().hasPermission(Permission.Administrator)

                            if (canSeeAuditLog) {
                                val actingModerator = guild.getAuditLogEntries {
                                    action = AuditLogEvent.MemberBanAdd
                                }.first { it.targetId == ban.userId }.userId.asUser()

                                val text = when (actingModerator) {
                                    kord.getSelf() -> {
                                        // ModerationExtension banned the user
                                        val recent = bot.findExtension<ModerationExtension>()!!.recentlyBannedUsers
                                        val mod = recent.remove(event.user.id)
                                        if (mod != null) {
                                            "${mod.mention} (${mod.tag}, via Rtuuy)"
                                        } else {
                                            "Unknown moderator (via Rtuuy)"
                                        }
                                    }
                                    null -> "Unknown (see audit log)"
                                    else -> "${actingModerator.mention} (${actingModerator.tag})"
                                }

                                field {
                                    name = "Responsible moderator"
                                    value = text
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

                            val canSeeAuditLog = guild.selfMember().hasPermission(Permission.ViewAuditLog) ||
                                    guild.selfMember().hasPermission(Permission.Administrator)

                            if (canSeeAuditLog) {
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

//        event<MemberUpdateEvent> {
//            check { inLadysnakeGuild() }
//
//            action {
//                try {
//                    val guilds = getGuilds().filter { it.id != event.guildId }
//
//                    for (guild in guilds) {
//                        val guildMember = guild.getMemberOrNull(event.member.id) ?: continue
//
//                        if (guildMember.timeoutUntil != event.member.timeoutUntil) {
//                            guildMember.edit {
//                                timeoutUntil = event.member.timeoutUntil
//                            }
//                        }
//                    }
//                } catch (e: RestRequestException) {
//                  logger.error(e) { "Failed to sync member timeout ${event.member.id} (JSON error ${e.error?.code})" }
//                }
//            }
//        }
    }

    private suspend fun getGuilds() = GUILDS.mapNotNull { kord.getGuild(it) }
}
