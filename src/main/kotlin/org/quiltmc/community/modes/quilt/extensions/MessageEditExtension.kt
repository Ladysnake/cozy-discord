/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.checks.hasPermission
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.ChoiceEnum
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.enumChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalInt
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.types.respond
import dev.kord.common.Color
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.rest.builder.message.modify.embed
import kotlinx.datetime.toInstant
import org.quiltmc.community.GUILDS
import org.quiltmc.community.OVERRIDING_USERS
import org.quiltmc.community.any
import org.quiltmc.community.copyFrom

class MessageEditExtension : Extension() {
	override val name = "message-modification"

	override suspend fun setup() {
        GUILDS.forEach {
            ephemeralSlashCommand {
                name = "edit"
                description = "Edit a message sent by ${kord.getSelf().username}"

                guild(it)

                ephemeralSubCommand(::PlainMessageArguments) {
                    name = "plain"
                    description = "Edit a message without an embed"

                    check {
                        any(
                            { hasPermission(Permission.Administrator) },
                            { failIf(event.interaction.user.id !in OVERRIDING_USERS) }
                        )
                    }

                    action {
                        val messageLink = arguments.messageLink
                        val newContent = arguments.content

                        val (guildId, channelId, messageId) = messageLink.messageIds()

                        var response = ""

                        getKoin().get<Kord>().getGuild(guildId)
                            ?.getChannelOfOrNull<GuildMessageChannel>(channelId)
                            ?.getMessageOrNull(messageId)
                            ?.edit {
                                content = newContent
                            }
                            ?: run {
                                response = "Could not find message"
                            }

                        if (response.isEmpty()) {
                            response = "Message edited"
                        }

                        respond {
                            content = response
                        }
                    }
                }

                ephemeralSubCommand(::EnumMessageArguments) {
                    name = "embed"
                    description = "Edit a message with an embed"

                    check {
                        any(
                            { hasPermission(Permission.Administrator) },
                            { failIf(event.interaction.user.id !in OVERRIDING_USERS) }
                        )
                    }

                    action {
                        val urlRegex = Regex(
                            """https?://(?:canary\.|ptb\.)?discord(?:app)?\.com/channels/\d+/\d+/\d+"""
                        )

                        val messageLink = arguments.messageLink

                        if (!urlRegex.matches(messageLink)) {
                            respond {
                                content = "Invalid message link"
                            }
                            return@action
                        }

                        val newContent = if (arguments.content.matches(urlRegex)) {
                            val (guildId, channelId, messageId) = messageLink.messageIds()

                            getKoin().get<Kord>().getGuild(guildId)
                                ?.getChannelOfOrNull<GuildMessageChannel>(channelId)
                                ?.getMessageOrNull(messageId)
                                ?.data?.content
                                ?: "Could not find message"
                        } else {
                            arguments.content.replace("\\n", "\n")
                        }

                        val embedPart = arguments.part

                        if (embedPart.requiresIndex && arguments.index == null) {
                            respond {
                                content = "You must specify an index"
                            }
                            return@action
                        }

                        val (guildId, channelId, messageId) = messageLink.messageIds()

                        val message = getKoin().get<Kord>().getGuild(guildId)
                            ?.getChannelOfOrNull<GuildMessageChannel>(channelId)
                            ?.getMessageOrNull(messageId)

                        if (message == null) {
                            respond {
                                content = "Could not find message"
                            }
                            return@action
                        }

                        val embed = message.embeds.firstOrNull()

                        if (embed == null) {
                            respond {
                                content = "Message does not have an embed"
                            }
                            return@action
                        }

                        message.edit {
                            embed {
                                copyFrom(embed)

                                when (embedPart) {
                                    EmbedPart.Title -> title = newContent
                                    EmbedPart.Description -> description = newContent
                                    EmbedPart.Field -> {
                                        val index = arguments.index!!

                                        when {
                                            index > embed.fields.size -> {
                                                respond {
                                                    content = "Index out of bounds"
                                                }
                                                return@edit
                                            }
                                            index == embed.fields.size -> field {
                                                name = "New field"
                                                value = newContent
                                            }
                                            else -> fields[index].value = newContent
                                        }
                                    }
                                    EmbedPart.FieldName -> {
                                        val index = arguments.index!!

                                        when {
                                            index > embed.fields.size -> {
                                                respond {
                                                    content = "Index out of bounds"
                                                }
                                                return@edit
                                            }
                                            index == embed.fields.size -> field {
                                                name = newContent
                                                value = "New field"
                                            }
                                            else -> fields[index].name = newContent
                                        }
                                    }
                                    EmbedPart.Color -> {
                                        @Suppress("MagicNumber") // hexadecimal base
                                        val newColor = newContent.toIntOrNull(16)
                                        if (newColor == null) {
                                            respond {
                                                content = "Invalid color (use a hex color with 6 digits)"
                                            }
                                            return@action
                                        }
                                        color = Color(newColor)
                                    }
                                    EmbedPart.AuthorIcon -> author?.icon = newContent
                                    EmbedPart.AuthorName -> author?.name = newContent
                                    EmbedPart.AuthorUrl -> author?.url = newContent
                                    EmbedPart.FooterImage -> footer?.icon = newContent
                                    EmbedPart.FooterText -> footer?.text = newContent
                                    EmbedPart.EmbedImage -> image = newContent
                                    EmbedPart.EmbedThumbnail -> thumbnail {
                                        url = newContent
                                    }
                                    EmbedPart.Timestamp -> try {
                                        timestamp = newContent.toInstant()
                                    } catch (e: IllegalArgumentException) {
                                        respond {
                                            content = "Invalid timestamp"
                                        }
                                        return@action
                                    }
                                }
                            }
                        }

                        respond {
                            content = "Successfully edited embed"
                        }
                    }
                }
            }
        }
	}

	private fun String.messageIds() = substringAfter(".com/channels/").split("/").map { Snowflake(it) }

	open class MessageArguments : Arguments() {
        val messageLink by string {
            name = "link"
            description = "The link to the message to edit"
        }
	}

	class PlainMessageArguments : MessageArguments() {
        val content by string {
            name = "content"
            description = "The new content of the message"
        }
	}

	class EnumMessageArguments : MessageArguments() {
        val part by enumChoice<EmbedPart> {
            name = "part"
            description = "The part of the embed to edit"

            typeName = "Part of Embed"
        }

        val content by string {
            name = "content"
            description = "The new content of selected part"
        }

        val index by optionalInt {
            name = "index"
            description = "The index of the selected part (ex: fields)"
        }
	}

	enum class EmbedPart(override val readableName: String, val requiresIndex: Boolean = false) : ChoiceEnum {
        Title("Title"),
        Description("Description"),
        Color("Color"),
        FooterImage("Footer Image"),
        FooterText("Footer Text"),
        Timestamp("Timestamp"),
        AuthorName("Author Name"),
        AuthorIcon("Author Icon"),
        AuthorUrl("Author URL"),
        EmbedImage("Embed Image"),
        EmbedThumbnail("Embed Thumbnail"),
        Field("Field Content", requiresIndex = true),
        FieldName("Field Name", requiresIndex = true),
	}
}
