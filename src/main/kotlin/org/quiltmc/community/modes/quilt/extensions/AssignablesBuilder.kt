/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions

import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.builder.components.emoji
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.Role
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.rest.builder.message.create.actionRow
import dev.kord.rest.builder.message.create.embed
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class AssignablesBuilder(val owner: Snowflake) {
	val assignables = mutableListOf<Assignable>()
	var lastChange: Instant = Clock.System.now()

	suspend fun build(outputChannel: MessageChannel) {
        outputChannel.createMessage {
            embed {
                title = "Role Assignment"
                description = "You can assign yourself a role by browsing the list below.\n" +
                        "If you want a role that is not listed, please suggest it in the appropriate channel."
            }

            @Suppress("MagicNumber")
            val assignablesByRow = assignables.chunked(5)

            for (row in assignablesByRow) {
                actionRow {
                    for (assignable in row) {
                        val role = assignable.role
                        val emoji = assignable.emoji

                        interactionButton(
                            ButtonStyle.Primary,
                            "assignables:assign-role:${role.id}"
                        ) {
                            when (emoji) {
                                is ReactionEmoji.Unicode -> emoji(emoji)
                                is ReactionEmoji.Custom -> emoji(emoji)
                                null -> {
                                    // ignore
                                }
                            }
                            label = role.name
                        }
                    }
                }
            }
        }
	}

	data class Assignable(
        val role: Role,
        val emoji: ReactionEmoji?,
	)
}
