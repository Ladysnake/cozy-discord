package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.*
import com.kotlindiscord.kord.extensions.parser.StringParser
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.download
import com.kotlindiscord.kord.extensions.utils.toReaction
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.asChannelOf
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.channel.withTyping
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.reply
import dev.kord.core.builder.components.emoji
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.rest.builder.message.create.actionRow
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.builder.message.modify.embed
import kotlinx.datetime.Clock
import org.koin.core.component.inject
import org.quiltmc.community.GUILDS
import org.quiltmc.community.MODERATOR_ROLES
import org.quiltmc.community.database.collections.LotteryCollection
import org.quiltmc.community.database.entities.Lottery
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.minutes

/**
 * What is this, you might ask? Well, this is
 * an extension to support things like role assignment
 * and lottery/giveaway functionality.
 */
class UserFunExtension : Extension() {
    override val name = "user-fun"

    private var currentAssignable: AssignablesBuilder? = null

    private val lotteryCollection: LotteryCollection by inject()

    override suspend fun setup() {
        // region: User role assignment

        ephemeralSlashCommand {
            name = "assignables"
            description = "Create a message to allow users to self-assign roles"

            ephemeralSubCommand {
                name = "start"
                description = "Start creating a new role selector"

                GUILDS.map { kord.getGuild(it) }.mapNotNull { it?.owner }.forEach(::allowUser)

                action {
                    if (currentAssignable != null && Clock.System.now() - currentAssignable!!.lastChange < 2.minutes) {
                        respond {
                            content = "There is already an assignable running!"
                        }
                        return@action
                    } else {
                        currentAssignable = AssignablesBuilder(user.id)
                        respond {
                            content = "Creating assignable; please continue with the other `/assignables` commands"
                        }
                    }
                }
            }

            ephemeralSubCommand {
                name = "cancel"
                description = "Cancel the current assignable"

                GUILDS.map { kord.getGuild(it) }.mapNotNull { it?.owner }.forEach(::allowUser)

                action {
                    if (currentAssignable == null) {
                        respond {
                            content = "There is no assignable running!"
                        }
                        return@action
                    }

                    val timeSinceLastChange = Clock.System.now() - currentAssignable!!.lastChange

                    if (currentAssignable!!.owner != user.id && timeSinceLastChange < 2.minutes) {
                        respond {
                            content = "You can only cancel your own assignable!"
                        }
                        return@action
                    }

                    currentAssignable = null

                    respond {
                        content = "Assignable cancelled"
                    }
                }
            }

            ephemeralSubCommand(::AssignableComplete) {
                name = "finish"
                description = "Finish the assignable and send the message"

                GUILDS.map { kord.getGuild(it) }.mapNotNull { it?.owner }.forEach(::allowUser)

                action {
                    val outputChannel = arguments.channel.asChannelOf<GuildMessageChannel>()

                    if (currentAssignable == null) {
                        respond {
                            content = "There is no assignable running!"
                        }
                        return@action
                    }

                    if (currentAssignable!!.owner != user.id) {
                        respond {
                            content = "You can only finish your own assignable!"
                        }
                        return@action
                    }

                    currentAssignable!!.build(outputChannel)

                    currentAssignable = null

                    respond {
                        content = "Assignable finished"
                    }
                }
            }

            ephemeralSubCommand(::AssignableAdd) {
                name = "add"
                description = "Add a role to the assignable"

                GUILDS.map { kord.getGuild(it) }.mapNotNull { it?.owner }.forEach(::allowUser)

                action {
                    if (currentAssignable == null) {
                        respond {
                            content = "There is no assignable running!"
                        }
                        return@action
                    }

                    if (currentAssignable!!.owner != user.id) {
                        respond {
                            content = "You can only add roles to your own assignable!"
                        }
                        return@action
                    }

                    val role = arguments.role
                    val emojiString = arguments.emoji

                    val emoji = if (emojiString?.matches(Regex("""^[Uu]\+[0-9a-fA-F]{4,5}$""")) == true) {
                        // get the unicode equivalent but parsing is hard
                        @Suppress("MagicNumber")
                        val unicode = emojiString.substring(2).toInt(16)
                        ReactionEmoji.Unicode(String(Character.toChars(unicode)))
                    } else {
                        // using kordex emoji converter is a lot easier than writing equivalent code
                        val converter = EmojiConverter()
                        if (converter.parse(StringParser(""), this, emojiString)) {
                            converter.parsed.toReaction()
                        } else {
                            null
                        }
                    }

                    val assignable = AssignablesBuilder.Assignable(role, emoji)
                    currentAssignable!!.assignables.add(assignable)
                    currentAssignable!!.lastChange = Clock.System.now()

                    respond {
                        content = "Added role ${role.mention} with emoji ${emoji?.mention}"
                    }
                }
            }
        }

        event<ButtonInteractionCreateEvent> {
            action {
                val (id, roleId) = event.interaction.component?.customId?.split(':') ?: return@action

                if (id != "assign-role") {
                    return@action
                }

                val guild = kord.getGuild(event.interaction.data.guildId.value!!)!!
                val member = guild.getMember(event.interaction.user.id)
                val role = Snowflake(roleId)

                if (role in member.roleIds) {
                    member.removeRole(role, "User removed role via assignable")
                    event.interaction.respondEphemeral {
                        content = "Removed role ${guild.getRole(role).name}"
                    }
                } else {
                    member.addRole(role, "User added role via assignable")
                    event.interaction.respondEphemeral {
                        content = "Added role ${guild.getRole(role).name}"
                    }
                }
            }
        }

        // endregion

        // region: Lottery / Giveaway

        ephemeralMessageCommand {
            name = "Create a drawing"

            MODERATOR_ROLES.forEach(::allowRole)

            action {
                val message = targetMessages.first()

                val attachments = message.attachments.associate { it.filename to it.download() }

                message.channel.createMessage {
                    embed {
                        title = "Drawing"
                        description = message.content
                        color = DISCORD_BLURPLE

                        field {
                            name = "Host"
                            value = message.author?.mention ?: "N/A"
                            inline = true
                        }

                        field {
                            name = "Current entrants"
                            value = "0"
                        }
                    }

                    for ((name, attachment) in attachments) {
                        addFile(name, ByteArrayInputStream(attachment))
                    }

                    actionRow {
                        interactionButton(
                            ButtonStyle.Primary,
                            "lottery:${message.id}:enter"
                        ) {
                            label = "Enter"
                            emoji(ReactionEmoji.Unicode("✅"))
                        }

                        interactionButton(
                            ButtonStyle.Danger,
                            "lottery:${message.id}:cancel"
                        ) {
                            label = "Cancel"
                            emoji(ReactionEmoji.Unicode("❌"))
                        }

                        interactionButton(
                            ButtonStyle.Secondary,
                            "lottery:${message.id}:close"
                        ) {
                            label = "Close"
                            emoji(ReactionEmoji.Unicode("🔒"))
                        }
                    }

                    val winnerCountDetectorRegex = Regex("""\d+ winners?""", RegexOption.IGNORE_CASE)

                    val winnerText = winnerCountDetectorRegex.find(message.content)?.value ?: "1 winner"
                    val winnerCount = winnerText.split(" ")[0].toIntOrNull() ?: 1

                    val newLottery = Lottery(
                        message.id,
                        mutableSetOf(),
                        winnerCount,
                    )

                    lotteryCollection.save(newLottery)
                }

                message.delete()

                respond {
                    content = "Drawing created!"
                }
            }
        }

        event<ButtonInteractionCreateEvent> {
            action {
                val interaction = event.interaction

                val idParts = interaction.componentId.split(":")

                @Suppress("MagicNumber") // yeef 3 is too big apparently
                if (idParts[0] != "lottery" || idParts.size != 3) {
                    return@action
                }

                val lotteryId = Snowflake(idParts[1])
                val action = idParts[2]

                val lottery = lotteryCollection.get(lotteryId) ?: return@action

                when (action) {
                    "enter" -> {
                        lottery.participants.add(interaction.user.id)
                        lotteryCollection.save(lottery)

                        val msgEmbed = interaction.message?.embeds?.firstOrNull()

                        interaction.message?.edit {
                            embed {
                                title = msgEmbed?.title
                                description = msgEmbed?.description
                                color = msgEmbed?.color ?: DISCORD_BLURPLE

                                for (field in msgEmbed?.fields ?: emptyList()) {
                                    field {
                                        name = field.name
                                        value = field.value
                                        inline = field.inline
                                    }
                                }

                                fields.first { it.name == "Current entrants" }.value =
                                    lottery.participants.size.toString()
                            }
                        }

                        interaction.respondEphemeral {
                            content = "You have entered the drawing!"
                        }
                    }
                    "cancel" -> {
                        val wasInLottery = lottery.participants.remove(interaction.user.id)

                        if (!wasInLottery) {
                            // ignore this user's strange request
                            interaction.acknowledgeEphemeral()
                            return@action
                        }

                        lotteryCollection.save(lottery)

                        val msgEmbed = interaction.message?.embeds?.firstOrNull()

                        interaction.message?.edit {
                            embed {
                                title = msgEmbed?.title
                                description = msgEmbed?.description
                                color = msgEmbed?.color ?: DISCORD_BLURPLE

                                for (field in msgEmbed?.fields ?: emptyList()) {
                                    field {
                                        name = field.name
                                        value = field.value
                                        inline = field.inline
                                    }
                                }

                                fields.first { it.name == "Current entrants" }.value =
                                    lottery.participants.size.toString()
                            }
                        }

                        interaction.respondEphemeral {
                            content = "You have left the drawing! (if you were in it)"
                        }
                    }
                    "close" -> {
                        val authorEmbedField = interaction.message?.embeds?.firstOrNull()
                            ?.fields?.firstOrNull { it.name == "Host" } ?: run {
                                interaction.respondEphemeral {
                                    content = "There may be an issue with this drawing..."
                                }
                                return@action
                            }

                        if (authorEmbedField.value != interaction.user.mention) {
                            interaction.respondEphemeral {
                                content = "You can't close this drawing!"
                            }
                            return@action
                        }

                        val channel = interaction.channel.asChannelOf<GuildMessageChannel>()
                        channel.withTyping {
                            val winners = lottery.participants.shuffled()
                                .mapNotNull { channel.guild.getMemberOrNull(it) }
                                .take(lottery.winners)

                            interaction.message?.edit {
                                components = mutableListOf()
                            }

                            interaction.respondEphemeral {
                                content = "Chosen ${winners.size} winners and creating the message..."
                            }

                            interaction.message!!.reply {
                                embed {
                                    title = "Event ended"
                                    description = "The event has ended! Here are the winners:\n\n" +
                                        winners.joinToString("\n") { it.mention }
                                }
                            }
                        }
                    }
                }
            }
        }

        // endregion

        // region: bean

        publicSlashCommand(::BeanArguments) {
            name = "bean"
            description = "Bean a user for all of their *terrible* actions"

            action {
                respond {
                    embed {
                        title = "User Beaned"
                        description = "Beaned ${arguments.user.mention}"
                        field {
                            name = "Reason"
                            value = arguments.reason
                        }
                        field {
                            name = "\"Responsible\" \"Moderator\""
                            value = user.mention
                        }
                    }
                }
            }
        }

        // endregion
    }

    class AssignableComplete : Arguments() {
        val channel by channel(
            "channel",
            "The channel to send the message to",
            requireSameGuild = true
        ) { _, channel ->
            if (channel.type !in listOf(ChannelType.GuildText, ChannelType.GuildNews)) {
                throw DiscordRelayedException("You must specify a text channel!")
            }
        }
    }

    class AssignableAdd : Arguments() {
        val role by role("role", "The role to add")

        val emoji by optionalString("emoji", "The emoji to use for this role")
    }

    class BeanArguments : Arguments() {
        val user by user("user", "The user to bean")
        val reason by string("reason", "The reason for the bean")
    }
}
