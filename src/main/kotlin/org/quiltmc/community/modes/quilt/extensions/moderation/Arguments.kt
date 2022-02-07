/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.moderation

import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.converters.Validator
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.utils.focusedOption
import com.kotlindiscord.kord.extensions.utils.suggestIntMap
import com.kotlindiscord.kord.extensions.utils.suggestLongMap
import dev.kord.core.behavior.RoleBehavior
import dev.kord.core.entity.KordEntity
import dev.kord.core.entity.channel.Channel
import dev.kord.core.entity.interaction.AutoCompleteInteraction
import dev.kord.core.entity.interaction.string
import org.quiltmc.community.modes.quilt.extensions.converters.defaultingIntChoice
import org.quiltmc.community.modes.quilt.extensions.converters.mentionable

// This is just to clean up ModerationExtension.kt a bit more.

interface ChannelTargetArguments {
    val channel: Channel?
}

open class RequiredUser(mentionableDesc: String, validator: Validator<KordEntity> = null) : Arguments() {
    val user by user {
        name = "user"
        description = mentionableDesc
        validate(validator)
    }
}

class PurgeArguments : Arguments(), ChannelTargetArguments {
    val amount by int {
        name = "amount"
        description = "The amount of messages to delete"

        validate {
            failIf("You must specify some amount of messages!") { value < 0 }
        }
    }

    val user by optionalUser {
        name = "user"
        description = "The user to purge messages from (all if omitted)"
    }

    override val channel by optionalChannel {
        name = "channel"
        description = "The channel to purge messages from (current if omitted)"
    }
}

class SlowModeArguments : Arguments(), ChannelTargetArguments {
    @Suppress("MagicNumber")
    val waitTime by int {
        name = "wait-time"
        description = "The minimum time to wait between messages in seconds"

        autoComplete {
            suggestIntMap(
                mapOf(
                    "1 second" to 1,
                    "5 seconds" to 5,
                    "10 seconds" to 10,
                    "30 seconds" to 30,
                    "1 minute" to 60,
                    "5 minutes" to 300,
                    "10 minutes" to 600,
                    "30 minutes" to 1_800,
                    "1 hour" to 3_600,
                    "2 hours" to 7_200,
                    "3 hours" to 10_800,
                    "6 hours" to 21_600,
                    "Disable" to 0
                )
            )
        }

        validate {
            failIf("You must specify a positive length!") { value < 0 }
        }
    }

    override val channel by optionalChannel {
        name = "channel"
        description = "The channel to set slowmode for (current if omitted)"
    }
}

class MentionArguments : Arguments() {
    val mentionable by mentionable {
        name = "entity"
        description = "The role or user to warn people about when mentioning them"

        validate {
            failIf("@everyone should be configured in server settings!") {
                value is RoleBehavior && (value as RoleBehavior).guildId == value.id
            }
        }
    }

    val allowDirectMentions by optionalBoolean {
        name = "allow-direct-mentions"
        description = "Whether to allow the role or user to be mentioned directly in a message"
    }

    val allowReplyMentions by optionalBoolean {
        name = "allow-reply-mentions"
        description = "Whether to allow the user (role not supported) to be mentioned in a reply to a message"
    }
}

open class RequiresReason(
    mentionableDesc: String,
    validator: Validator<KordEntity> = null
) : RequiredUser(mentionableDesc, validator) {
    val reason by string {
        name = "reason"
        description = "The reason for the action"
    }
}

class BanArguments : RequiresReason("The user to ban") {
    @Suppress("MagicNumber")
    val length by defaultingLong {
        name = "length"
        description = "The length of the ban in seconds, 0 for indefinite, or -1 to end (default indefinite)"
        defaultValue = 0L

        autoComplete {
            val map = mapFrom(
                "second" to 1,
                "minute" to 60,
                "hour" to 3600,
                "day" to 86_400,
                "week" to 604_800,
                "month" to 2_629_746,
                "year" to 31_557_600,
                defaultMap = mapOf(
                    "1 minute" to 60,
                    "5 minutes" to 300,
                    "10 minutes" to 600,
                    "30 minutes" to 1_800,
                    "1 hour" to 3_600,
                    "2 hours" to 7_200,
                    "3 hours" to 10_800,
                    "6 hours" to 21_600,
                    "1 day" to 86_400,
                    "2 days" to 172_800,
                    "3 days" to 259_200,
                    "1 week" to 604_800,
                    "2 weeks" to 1_209_600,
                    "3 weeks" to 1_814_400,
                    "1 month" to 2_592_000,
                    "2 months" to 5_184_000,
                    "3 months" to 7_168_000,
                    "6 months" to 15_552_000,
                    "1 year" to 31_556_952,
                    "forever" to 0,
                    "unban" to -1
                )
            )

            suggestLongMap(map)
        }
    }

    val daysToDelete by banDeleteDaySelector()
}

class TimeoutArguments : RequiresReason("The user to timeout") {
    @Suppress("MagicNumber")
    val length by defaultingLong {
        name = "length"
        description = "The length of the timeout (default 5 minutes)"
        defaultValue = 300

        autoComplete {
            val map = mapFrom(
                "second" to 1L,
                "minute" to 60L,
                "hour" to 3600L,
                "day" to 86_400L,
                "week" to 604_800L,
                defaultMap = mapOf(
                    "1 minute" to 60L,
                    "5 minutes" to 300L,
                    "10 minutes" to 600L,
                    "30 minutes" to 1_800L,
                    "1 hour" to 3_600L,
                    "2 hours" to 7_200L,
                    "3 hours" to 10_800L,
                    "6 hours" to 21_600L,
                    "1 day" to 86_400L,
                    "2 days" to 172_800L,
                    "3 days" to 259_200L,
                    "1 week" to 604_800L,
                    "2 weeks" to 1_209_600L,
                    "3 weeks" to 1_814_400L,
                    "1 month" to 2_592_000L,
                    "Remove timeout" to -1L
                )
            )

            suggestLongMap(map)
        }

        validate {
            failIfNot("length must be between 0 and 28 days") {
                value in -1..86_400 * 28
            }
        }
    }
}

class ActionArguments : Arguments() {
    val user by snowflake {
        name = "user"
        description = "The user to perform the action on, as their user ID"
    }

    val action by stringChoice {
        name = "action"
        description = "The action to take on the user"

        choices["ban"] = "ban"
        choices["timeout"] = "timeout"
    }

    val reason by string {
        name = "reason"
        description = "The reason for the action"
    }

    @Suppress("MagicNumber")
    val length by defaultingLong {
        name = "length"
        description = "The length of the action (default 1 month)"
        defaultValue = 2_629_746

        autoComplete {
            val map = mapFrom(
                "second" to 1,
                "minute" to 60,
                "hour" to 3600,
                "day" to 86_400,
                "week" to 604_800,
                "month" to 2_629_746,
                "year" to 31_557_600,
                defaultMap = mapOf(
                    "1 minute" to 60,
                    "5 minutes" to 300,
                    "10 minutes" to 600,
                    "30 minutes" to 1_800,
                    "1 hour" to 3_600,
                    "2 hours" to 7_200,
                    "3 hours" to 10_800,
                    "6 hours" to 21_600,
                    "1 day" to 86_400,
                    "2 days" to 172_800,
                    "3 days" to 259_200,
                    "1 week" to 604_800,
                    "2 weeks" to 1_209_600,
                    "3 weeks" to 1_814_400,
                    "1 month" to 2_592_000,
                    "2 months" to 5_184_000,
                    "3 months" to 7_168_000,
                    "6 months" to 15_552_000,
                    "1 year" to 31_556_952,
                    "forever" to 0,
                    "remove" to -1
                )
            )

            suggestLongMap(map)
        }
    }

    val banDeleteDays by banDeleteDaySelector()
}

class NoteArguments : Arguments() {
    val note by string {
        name = "note"
        description = "The note to add"
    }

    val messageId by optionalSnowflake {
        name = "message-id"
        description = "An optional log message to add the note to"
    }

    val user by optionalUser {
        name = "user"
        description = "An optional user to add the note to, if message-id is not specified"
    }
}

@Suppress("MagicNumber")
internal fun Arguments.banDeleteDaySelector() = defaultingIntChoice {
    name = "delete-days"
    description = "The amount of days to delete messages from the user's history"
    defaultValue = 0
    choices = buildMap<String, Int> {
        for (i in 0..6) {
            put("$i days", i)
        }

        // two special cases for displayed names
        // 1 day
        put("1 day", 1)
        remove("1 days")
        // 7 days
        put("1 week", 7)
    }.toMutableMap() // kordex requires a mutable map
}

internal fun AutoCompleteInteraction.mapFrom(
    vararg conversions: Pair<String, Long>,
    defaultMap: Map<String, Long> = mapOf(),
): Map<String, Long> {
    val specifiedLength = focusedOption.string().substringBefore(' ').toLongOrNull()
    return if (specifiedLength != null) {
        buildMap {
            val pluralModifier = if (specifiedLength == 1L) "" else "s"
            for ((str, length) in conversions) {
                put("$specifiedLength $str$pluralModifier", length * specifiedLength)
            }
        }
    } else {
        defaultMap
    }
}
