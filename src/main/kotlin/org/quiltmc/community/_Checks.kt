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
import dev.kord.core.entity.Member
import dev.kord.core.event.Event
import dev.kord.core.event.interaction.InteractionCreateEvent
import mu.KotlinLogging
import org.quiltmc.community.database.collections.ServerSettingsCollection
import org.quiltmc.community.database.getSettings

private val modChecks: List<suspend Member.() -> Boolean> = listOf(
	{ roleIds.any { it in MODERATOR_ROLES } },
	{ isOwner() },
	{ hasPermission(Permission.Administrator) },
	{ id in OVERRIDING_USERS },
	{ guild.getSettings()?.moderatorRoles?.intersect(roleIds).isNullOrEmpty().not() },
)

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

	val guild = event.kord.getGuildOrNull(MAIN_GUILD)!!
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

	if (member.hasPermission(perm) || member.id in OVERRIDING_USERS) {
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

@Suppress("UnusedPrivateMember") // keep upstream mostly in sync
suspend fun CheckContext<*>.hasBaseModeratorRole(includeCommunityManagers: Boolean = true) {
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
			if (modChecks.none { member.it() }) {
				logger.failed("Member does not have a Ladysnake base moderator role")

				fail("Must be a Ladysnake moderator, with the `Moderators` role")
			}
		}
	}
}

@Suppress("UnusedPrivateMember") // keep upstream mostly in sync
suspend fun CheckContext<*>.notHasBaseModeratorRole(includeCommunityManagers: Boolean = true) {
	if (!passed) {
		return
	}

	val logger = KotlinLogging.logger("org.quiltmc.community.notHasBaseModeratorRole")
	val member = memberFor(event)?.asMemberOrNull()

	if (member == null) {  // Not on a guild, fail.
		logger.nullMember(event)

		fail()
	} else {
		if (modChecks.any { member.it() }) {
			logger.failed("Member has a Ladysnake base moderator role")

			fail("Must **not** be a Ladysnake moderator")
		}
	}
}

suspend fun <T : Event> CheckContext<T>.any(vararg checks: suspend CheckContext<T>.() -> Unit) {
	if (!passed) {
        return
	}

	checks.forEach {
        val context = CheckContext(event, locale)
        context.it()
        if (context.passed) {
            return
        }
	}

	fail()
}

suspend fun CheckContext<InteractionCreateEvent>.isAdminOrHasOverride() {
	any(
        { hasPermissionInMainGuild(Permission.Administrator) },
        { failIf(event.interaction.user.id !in OVERRIDING_USERS) }
	)
}
