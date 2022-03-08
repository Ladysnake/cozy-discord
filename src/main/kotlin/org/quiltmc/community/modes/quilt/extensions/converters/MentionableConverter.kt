/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.converters

import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.commands.Argument
import com.kotlindiscord.kord.extensions.commands.CommandContext
import com.kotlindiscord.kord.extensions.commands.chat.ChatCommandContext
import com.kotlindiscord.kord.extensions.commands.converters.SingleConverter
import com.kotlindiscord.kord.extensions.commands.converters.Validator
import com.kotlindiscord.kord.extensions.modules.annotations.converters.Converter
import com.kotlindiscord.kord.extensions.modules.annotations.converters.ConverterType
import com.kotlindiscord.kord.extensions.parser.StringParser
import com.kotlindiscord.kord.extensions.utils.users
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.KordEntity
import dev.kord.core.entity.Member
import dev.kord.core.entity.Role
import dev.kord.core.entity.interaction.MentionableOptionValue
import dev.kord.core.entity.interaction.OptionValue
import dev.kord.rest.builder.interaction.MentionableBuilder
import dev.kord.rest.builder.interaction.OptionsBuilder
import kotlinx.coroutines.flow.firstOrNull

@Converter(
    "mentionable",

    types = [ConverterType.LIST, ConverterType.OPTIONAL, ConverterType.SINGLE]
)
class MentionableConverter(
    private var useReply: Boolean = true,
    override var validator: Validator<KordEntity> = null
) : SingleConverter<KordEntity>() {
    override val signatureTypeString = "converters.member.signatureType"

    override suspend fun parse(parser: StringParser?, context: CommandContext, named: String?): Boolean {
        if (useReply && context is ChatCommandContext<*>) {
            val messageReference = context.message.asMessage().messageReference

            if (messageReference != null) {
                val member = messageReference.message?.asMessage()?.getAuthorAsMember()

                if (member != null) {
                    parsed = member
                    return true
                }
            }
        }

        val arg = named ?: parser?.parseNext()?.data ?: return false

        parsed = findMember(arg, context) as KordEntity?
            ?: findRole(arg, context) as KordEntity?
            ?: throw DiscordRelayedException("Could not find member or role with name $arg")

        return true
    }

    private suspend fun findMember(arg: String, context: CommandContext): Member? {
        val user = if (arg.startsWith("<@") && arg.endsWith(">")) {
            val id = arg.substring(2, arg.length - 1).replace("!", "")

            try {
                kord.getUser(Snowflake(id))
            } catch (e: NumberFormatException) {
                throw DiscordRelayedException(
                    context.translate("converters.member.error.invalid", replacements = arrayOf(id))
                )
            }
        } else {
            try { // Try for a user ID first
                kord.getUser(Snowflake(arg))
            } catch (e: NumberFormatException) { // It's not an ID, let's try the tag
                if (!arg.contains("#")) {
                    null
                } else {
                    kord.users.firstOrNull { user ->
                        user.tag.equals(arg, true)
                    }
                }
            }
        }

        return user?.asMember(context.getGuild()?.id ?: return null)
    }

    private suspend fun findRole(arg: String, context: CommandContext): Role? {
        return if (arg.startsWith("<@&") && arg.endsWith(">")) {
            @Suppress("MagicNumber")
            val id = arg.substring(3, arg.length - 1).replace("!", "")

            try {
                context.getGuild()?.getRole(Snowflake(id)) ?: return null
            } catch (e: NumberFormatException) {
                throw DiscordRelayedException(
                    context.translate("converters.member.error.invalid", replacements = arrayOf(id))
                )
            }
        } else {
            // Try for a role ID first
            try {
                context.getGuild()?.getRole(Snowflake(arg)) ?: return null
            } catch (e: NumberFormatException) {
                // It's not an ID, let's try the name
                context.getGuild()?.roles?.firstOrNull { role ->
                    role.name.equals(arg, true)
                }
            }
        }
    }

    override suspend fun parseOption(context: CommandContext, option: OptionValue<*>): Boolean {
        val optionValue = (option as? MentionableOptionValue)?.value ?: return false
        parsed = optionValue as KordEntity // in theory this should never fail

        return true
    }

    override suspend fun toSlashOption(arg: Argument<*>): OptionsBuilder =
        MentionableBuilder(arg.displayName, arg.description).apply { required = true }
}
