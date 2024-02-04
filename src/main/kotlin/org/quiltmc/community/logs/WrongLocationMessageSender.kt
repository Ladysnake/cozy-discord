/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.logs

import com.kotlindiscord.kord.extensions.checks.channelFor
import com.kotlindiscord.kord.extensions.checks.guildFor
import dev.kord.core.behavior.channel.asChannelOfOrNull
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.event.Event
import org.quiltmc.community.LOG_PARSING_CONFINEMENT
import org.quiltmc.community.cozy.modules.logs.data.Log
import org.quiltmc.community.cozy.modules.logs.data.Order
import org.quiltmc.community.cozy.modules.logs.types.LogParser

class WrongLocationMessageSender : LogParser() {
	override val identifier: String = "wrong-location-message-sender"
	override val order = Order(Int.MAX_VALUE) // be the last parser to run (to destroy the log if necessary)

	override suspend fun predicate(log: Log, event: Event): Boolean {
		val channel = channelFor(event)?.asChannelOfOrNull<TextChannel>() ?: return false
		val guild = guildFor(event) ?: return false
		val allowedChannels = LOG_PARSING_CONFINEMENT[guild.id] ?: return false
		if (channel.id in allowedChannels) return false

		channel.createMessage(
			"This log was sent in wrong location. No parsing will be done.\n" +
			if (allowedChannels.size > 1) {
				"Please use one of ${allowedChannels.joinToString(", ") { "<#$it>" }} to parse logs."
			} else {
				"Please use <#${allowedChannels.single()}> to parse logs."
			}
		)
		return false
	}

	override suspend fun process(log: Log) = Unit
}
