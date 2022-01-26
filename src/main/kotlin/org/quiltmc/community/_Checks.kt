/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community

import com.kotlindiscord.kord.extensions.checks.*
import com.kotlindiscord.kord.extensions.checks.types.CheckContext
import com.kotlindiscord.kord.extensions.utils.hasPermission
import com.kotlindiscord.kord.extensions.utils.translate
import dev.kord.common.entity.Permission
import mu.KotlinLogging
import org.quiltmc.community.database.collections.ServerSettingsCollection

suspend fun CheckContext<*>.inLadysnake() {
    anyGuild()

    if (!passed) {
        return
    }

    val logger = KotlinLogging.logger("org.quiltmc.community.inLadysnake")

    val collection = getKoin().get<ServerSettingsCollection>()
    val settings = collection.getLadysnake()

    if (settings == null) {
        logger.failed("Ladysnake server hasn't been configured yet.")
        fail("Ladysnake server hasn't been configured yet.")
    } else {
        inGuild(settings._id)
    }
}

suspend fun CheckContext<*>.inYoutube() {
    anyGuild()

    if (!passed) {
        return
    }

    val logger = KotlinLogging.logger("org.quiltmc.community.inYoutube")

    val collection = getKoin().get<ServerSettingsCollection>()
    val settings = collection.getYoutube()

    if (settings == null) {
        logger.failed("Youtube server hasn't been configured yet.")
        fail("Youtube server hasn't been configured yet.")
    } else {
        inGuild(settings._id)
    }
}

suspend fun CheckContext<*>.hasPermissionInMainGuild(perm: Permission) {
    anyGuild()

    if (!passed) {
        return
    }

    val logger = KotlinLogging.logger("org.quiltmc.community.hasPermissionInMainGuild")
    val user = userFor(event)

    if (user == null) {
        logger.failed("Event did not concern a user.")
        fail()

        return
    }

    val guild = event.kord.getGuild(MAIN_GUILD)!!
    val member = guild.getMemberOrNull(user.id)

    if (member == null) {
        logger.failed("User is not on the main guild.")

        fail(
            translate(
                "checks.inGuild.failed",
                replacements = arrayOf(guild.name),
            )
        )

        return
    }

    if (member.hasPermission(perm)) {
        logger.passed()
    } else {
        logger.failed("User does not have permission: $perm")
        fail("Must have permission **${perm.translate(locale)}** on **${guild.name}**")
    }
}

suspend fun CheckContext<*>.inLadysnakeGuild() {
    if (!passed) {
        return
    }

    val logger = KotlinLogging.logger("org.quiltmc.community.inLadysnakeGuild")
    val guild = guildFor(event)

    if (guild == null) {
        logger.nullGuild(event)

        fail("Must be in one of the Ladysnake servers")
    } else {
        if (guild.id !in GUILDS) {
            fail("Must be in one of the Ladysnake servers")
        }
    }
}

suspend fun CheckContext<*>.hasBaseModeratorRole() {
    if (!passed) {
        return
    }

    inLadysnakeGuild()

    if (this.passed) {  // They're on a Ladysnake guild
        val logger = KotlinLogging.logger("org.quiltmc.community.hasBaseModeratorRole")
        val member = memberFor(event)?.asMemberOrNull()

        if (member == null) {  // Shouldn't happen, but you never know
            logger.nullMember(event)

            fail()
        } else {
            if (!member.roleIds.any { it in MODERATOR_ROLES }) {
                logger.failed("Member does not have a Ladysnake base moderator role")

                fail("Must be a Ladysnake moderator, with the `Moderators` role")
            }
        }
    }
}

suspend fun CheckContext<*>.notHasBaseModeratorRole() {
    if (!passed) {
        return
    }

    val logger = KotlinLogging.logger("org.quiltmc.community.notHasBaseModeratorRole")
    val member = memberFor(event)?.asMemberOrNull()

    if (member == null) {  // Not on a guild, fail.
        logger.nullMember(event)

        fail()
    } else {
        if (member.roleIds.any { it in MODERATOR_ROLES }) {
            logger.failed("Member has a Ladysnake base moderator role")

            fail("Must **not** be a Ladysnake moderator")
        }
    }
}
