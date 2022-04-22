/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.checks.hasPermission
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.application.slash.publicSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.*
import com.kotlindiscord.kord.extensions.pagination.MessageButtonPaginator
import com.kotlindiscord.kord.extensions.pagination.builders.PaginatorBuilder
import com.kotlindiscord.kord.extensions.parser.StringParser
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.download
import com.kotlindiscord.kord.extensions.utils.getKoin
import com.kotlindiscord.kord.extensions.utils.suggestIntMap
import com.kotlindiscord.kord.extensions.utils.toReaction
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.UserBehavior
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
import dev.kord.rest.builder.message.create.allowedMentions
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.builder.message.modify.embed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock
import org.koin.core.component.inject
import org.quiltmc.community.*
import org.quiltmc.community.database.collections.LotteryCollection
import org.quiltmc.community.database.collections.QuoteCollection
import org.quiltmc.community.database.entities.Lottery
import org.quiltmc.community.modes.quilt.extensions.rotatinglog.MessageLogExtension
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.minutes

private const val QUOTES_PER_PAGE = 5

/**
 * What is this, you might ask? Well, this is
 * an extension to support things like role assignment
 * and lottery/giveaway functionality.
 */
class UserFunExtension : Extension() {
    override val name = "user-fun"

    private var currentAssignable: AssignablesBuilder? = null

    private val lotteryCollection: LotteryCollection by inject()

    private val quoteCollection: QuoteCollection by inject()

    override suspend fun setup() {
        // region: User role assignment

        ephemeralSlashCommand {
            name = "assignables"
            description = "Create a message to allow users to self-assign roles"

            ephemeralSubCommand {
                name = "start"
                description = "Start creating a new role selector"

                check { any(
                    { hasPermission(Permission.Administrator) },
                    { failIf(event.interaction.user.id !in OVERRIDING_USERS) }
                ) }

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

                check { any(
                    { hasPermission(Permission.Administrator) },
                    { failIf(event.interaction.user.id !in OVERRIDING_USERS) }
                ) }

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

                check { any(
                    { hasPermission(Permission.Administrator) },
                    { failIf(event.interaction.user.id !in OVERRIDING_USERS) }
                ) }

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

                check { any(
                    { hasPermission(Permission.Administrator) },
                    { failIf(event.interaction.user.id !in OVERRIDING_USERS) }
                ) }

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
                val buttonId = event.interaction.component.customId?.split(':') ?: return@action
                @Suppress("MagicNumber") // but it is right, 3 is a magic number, yes it is, it's a magic number
                if (buttonId.size != 3 || buttonId[0] != "assignables") {
                    // somewhere in the ancient mystic trinity
                    // you get 3, that's a magic number
                    // the past and the present and the future
                    // faith and hope and charity
                    // the heart and the brain and the body
                    // give you 3, that's a magic number

                    // it takes 3 legs to make a tripod or to make a table stand
                    // it takes 3 wheels to make a vehicle called a tricycle
                    // every triangle has 3 corners, every triangle has 3 sides
                    // no more, no less
                    // you don't have to guess
                    // when it's 3, you can see it's a magic number

                    // a man and a woman had a little baby
                    // yes they did, they had 3 in the family
                    // and that's a magic number

                    return@action
                }

                val (_, id, roleId) = buttonId

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

                        val msgEmbed = interaction.message.embeds.firstOrNull()

                        interaction.message.edit {
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
                            interaction.deferEphemeralResponse()
                            return@action
                        }

                        lotteryCollection.save(lottery)

                        val msgEmbed = interaction.message.embeds.firstOrNull()

                        interaction.message.edit {
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
                        val authorEmbedField = interaction.message.embeds.firstOrNull()
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

                            interaction.message.edit {
                                components = mutableListOf()
                            }

                            interaction.respondEphemeral {
                                content = "Chosen ${winners.size} winners and creating the message..."
                            }

                            interaction.message.reply {
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

        // region: Quotes

        publicSlashCommand {
            name = "quote"
            description = "Get or add a quote"

            publicSubCommand(::QuoteArguments) {
                name = "get"
                description = "Get a quote by its ID"

                action {
                    val id = arguments.quote
                    val quote = quoteCollection.get(id)

                    if (quote == null) {
                        respond {
                            content = "Quote $id not found!"
                        }
                    } else {
                        respond {
                            content = """
                                |#$id:
                                |> ${quote.quote.replace("\n", "\n> ")}
                                |*- ${quote.author}*
                            """.trimMargin()
                            allowedMentions {
                                // By defining this, all mentions are prohibited, so nobody gets pinged from the quote
                            }
                        }
                    }
                }
            }

            publicSubCommand(::QuoteAddArguments) {
                name = "add"
                description = "Add a quote"

                check { inLadysnakeGuild() }

                action {
                    val quoteId = addQuote(arguments.quote, arguments.author, guild!!.id, user.asUser())

                    respond {
                        content = "Quote #$quoteId added!"
                    }
                }
            }

            ephemeralSubCommand(::QuoteArguments) {
                name = "delete"
                description = "Delete a quote, if you have permission"

                check { hasBaseModeratorRole() }

                action {
                    val id = arguments.quote
                    val quote = quoteCollection.get(id)

                    if (quote == null) {
                        respond {
                            content = "Quote $id not found!"
                        }
                    } else {
                        quoteCollection.delete(id)

                        respond {
                            content = "Quote #$id deleted!"
                        }
                    }
                }
            }

            ephemeralSubCommand {
                name = "list"
                description = "List all quotes"

                check { hasBaseModeratorRole() }

                action {
                    val quotes = quoteCollection.getAll()
                    val user = user.asUser()

                    val paginator = PaginatorBuilder()

                    val pageTitle = "List of quotes"

                    quotes.map {
                        val author = it.author
                        val quote = it.quote
                        val id = it._id

                        """
                                |#$id:
                                |> $quote
                                |*- $author*
                            """.trimMargin()
                    }.toList().chunked(QUOTES_PER_PAGE).forEach { quotesInPage ->
                        paginator.page {
                            title = pageTitle
                            description = quotesInPage.joinToString("\n\n")
                        }
                    }

                    val channel = user.getDmChannelOrNull()
                    if (channel == null) {
                        respond {
                            content = "**Error:** You must allow DMs from the but to list quotes."
                        }
                    } else {
                        val messagePaginator = MessageButtonPaginator(targetChannel = channel, builder = paginator)
                        messagePaginator.send()

                        respond {
                            content = "Sent! Check your DMs!"
                        }
                    }
                }
            }
        }

        publicMessageCommand {
            name = "Add to quotes"

            check { inLadysnakeGuild() }

            action {
                val message = event.interaction.getTarget().content
                val author = event.interaction.getTarget().getAuthorAsMember()?.displayName
                    ?: event.interaction.getTarget().data.author.username
                val guild = event.interaction.target.getChannel().data.guildId.value!!
                val initiator = event.interaction.user

                val id = addQuote(message, author, guild, initiator)

                respond {
                    content = "Quote #$id added!"
                }
            }
        }

        // endregion
    }

    private suspend fun addQuote(message: String, author: String?, guildId: Snowflake, user: UserBehavior): Int {
        val id = quoteCollection.new(message, author ?: "Anonymous")

        bot.findExtension<MessageLogExtension>()?.getRotator(guildId)?.logOther {
            embed {
                title = "Quote Added"
                description = message
                field {
                    name = "Author"
                    value = author ?: "Anonymous"
                    inline = true
                }
                field {
                    name = "ID"
                    value = "$id"
                    inline = true
                }
                field {
                    name = "User who added"
                    value = "${user.mention} (${user.id})"
                    inline = true
                }
            }
        }

        return id
    }

    class AssignableComplete : Arguments() {
        val channel by channel {
            name = "channel"
            description = "The channel to send the message to"
            requireSameGuild = true

            validate {
                failIf { value.type !in listOf(ChannelType.GuildText, ChannelType.GuildNews) }
            }
        }
    }

    class AssignableAdd : Arguments() {
        val role by role {
            name = "role"
            description = "The role to add"
        }

        val emoji by optionalString {
            name = "emoji"
            description = "The emoji to use for this role"
        }
    }

    class BeanArguments : Arguments() {
        val user by user {
            name = "user"
            description = "The user to bean"
        }

        val reason by string {
            name = "reason"
            description = "The reason for the bean"
        }
    }

    class QuoteArguments : Arguments() {
        val quote by int {
            name = "id"
            description = "The ID of the quote (text will search for the quote)"

            autoComplete {
                val currentValue = focusedOption.value

                @Suppress("MagicNumber")
                val quotes = getKoin().get<QuoteCollection>().searchByContent(currentValue)
                    .map { "${it.author} - ${it.quote}" to it._id }
                    .map { (s, i) -> (if (s.length > 100) s.substring(0..96) + "..." else s) to i }
                    .toList().toMap()

                suggestIntMap(quotes)
            }
        }
    }

    class QuoteAddArguments : Arguments() {
        val quote by string {
            name = "quote"
            description = "The quote to add"
        }

        val author by optionalString {
            name = "author"
            description = "The author of the quote, or Anonymous if not specified"
        }
    }
}
