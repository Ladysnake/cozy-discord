/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import dev.kord.core.event.gateway.ReadyEvent
import io.github.oshai.kotlinlogging.KotlinLogging

class ConsoleLogExtension : Extension() {
	private val logger = KotlinLogging.logger {}

	override val name = "console-logging"

	override suspend fun setup() {
		event<ReadyEvent> {
			action {
				logger.info { "GLOBAL COMMANDS" }
				kord.getGlobalApplicationCommands().collect {
					logger.info { "${it.name} - ${it.id}" }
				}

				kord.guilds.collect { guild ->
					logger.info { "GUILD: ${guild.name}" }
					guild.getApplicationCommands().collect {
						logger.info { "${it.name} - ${it.id}" }
					}
				}

				logger.info { "DONE" }
			}
		}
	}
}
