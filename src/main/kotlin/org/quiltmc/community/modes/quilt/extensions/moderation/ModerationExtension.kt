/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.moderation

import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.checks.isNotBot
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.EphemeralSlashCommandContext
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.defaultingNumberChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.Validator
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.time.TimestampType
import com.kotlindiscord.kord.extensions.time.toDiscord
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.authorId
import com.kotlindiscord.kord.extensions.utils.dm
import com.kotlindiscord.kord.extensions.utils.scheduling.Scheduler
import com.soywiz.korio.async.toChannel
import dev.kord.common.entity.MessageFlag
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.optionalInt
import dev.kord.core.any
import dev.kord.core.behavior.RoleBehavior
import dev.kord.core.behavior.ban
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.entity.*
import dev.kord.core.entity.channel.Channel
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.UserMessageCreateBuilder
import dev.kord.rest.builder.message.create.allowedMentions
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.builder.message.modify.embed
import dev.kord.rest.json.request.ChannelModifyPatchRequest
import dev.kord.rest.request.RestRequestException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.koin.core.component.inject
import org.quiltmc.community.*
import org.quiltmc.community.database.collections.InvalidMentionsCollection
import org.quiltmc.community.database.collections.ServerSettingsCollection
import org.quiltmc.community.database.collections.UserRestrictionsCollection
import org.quiltmc.community.database.entities.InvalidMention
import org.quiltmc.community.database.entities.InvalidMention.Type.*
import org.quiltmc.community.database.entities.UserRestrictions
import org.quiltmc.community.modes.quilt.extensions.converters.defaultingIntChoice
import org.quiltmc.community.modes.quilt.extensions.converters.intChoice
import org.quiltmc.community.modes.quilt.extensions.converters.mentionable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ModerationExtension(
    private val enabledModules: List<Module> = Module.allSupported,
    private val modReportChannel: Snowflake? = null,
) : Extension() {
    private val logger = KotlinLogging.logger {}

    override val name = "moderation"

    private val scheduler = Scheduler()

    private val invalidMentions: InvalidMentionsCollection by inject()

    private val settings: ServerSettingsCollection by inject()

    private val userRestrictions: UserRestrictionsCollection by inject()

    override suspend fun setup() {
        if (Module.PURGE in enabledModules) {
            ephemeralSlashCommand(::PurgeArguments) {
                name = "purge"
                description = "Purge a number of messages from a channel"

                MODERATOR_ROLES.forEach(::allowRole)

                action {
                    val channel = arguments.channel ?: channel.asChannel()
                    val checkUsers = arguments.user != null
                    // checked in verifier
                    val targetUser = arguments.user as Member?
                    if (channel !is GuildMessageChannel) {
                        respond {
                            content = "This command can only target a text channel, and ${channel.mention} is not one."
                        }
                        return@action
                    }
                    val deletedMessageList = mutableListOf<Snowflake>()
                    val messages = channel.getMessagesBefore(channel.lastMessage!!.id).toChannel()
                    for (message in messages) {
                        if (message.data.flags.value?.contains(MessageFlag.Ephemeral) == true) {
                            continue // skip ephemeral messages
                        }
                        if (!checkUsers || message.author == targetUser) {
                            deletedMessageList.add(message.data.id)
                        }

                        if (deletedMessageList.size >= arguments.amount) {
                            break
                        }
                    }
                    channel.bulkDelete(deletedMessageList, reason = "Purge command by ${user.softMention()}")

                    respond {
                        content = "Purged ${deletedMessageList.size} messages."
                    }
                }
            }
        }
        if (Module.CHANNEL_SLOWMODE in enabledModules) {
            ephemeralSlashCommand(::ChannelSlowModeArguments) {
                name = "slowmode"
                description = "Sets a channel's slowmode."

                MODERATOR_ROLES.forEach(::allowRole)

                ephemeralSubCommand(::ChannelDefaultSlowModeArguments) {
                    name = "preset"
                    description = "Sets a channel's slowmode with a preset i."

                    MODERATOR_ROLES.forEach(::allowRole)

                    action(::slowmode)
                }

                ephemeralSubCommand(::ChannelSlowModeArguments) {
                    name = "custom"
                    description = "Sets a channel's slowmode with a custom i."

                    MODERATOR_ROLES.forEach(::allowRole)

                    action(::slowmode)
                }

                ephemeralSubCommand({
                    object : Arguments(), ChannelTargetArguments, RequiredInt {
                        override val i = 0
                        override val channel by optionalChannel {
                            name = "channel"
                            description = "The channel to disable slowmode in"
                        }
                    }
                }) {
                    name = "off"
                    description = "Disables slowmode in a channel."

                    MODERATOR_ROLES.forEach(::allowRole)

                    action(::slowmode) // yes i made a custom object for the arguments just to do this
                }
            }
        }
        if (Module.LIMIT_SPAM in enabledModules) {
            // map of user ids to the most recent message ids
            val recentMessages = mutableMapOf<Snowflake, MutableList<Snowflake>>()
            event<MessageCreateEvent> {
                check { isNotBot() } // oh man my bot is going crazy
                check { notHasBaseModeratorRole() } // mods can spam :pineapple:

                action {
                    if (event.member?.roles?.any { it.id in MODERATOR_ROLES } == true) {
                        return@action
                    }
                    val user = event.message.author?.id ?: return@action
                    val messageId = event.message.id
                    val recentMessagesForUser = recentMessages.getOrPut(user) { mutableListOf() }

                    recentMessagesForUser.add(messageId)

                    // remove all messages older than 1 minute
                    recentMessagesForUser.removeAll {
                        (it.timeMark + 60.seconds).hasPassedNow()
                    }

                    // also check for spam that happened in a second
                    val spamCheck = recentMessagesForUser.filterNot {
                        (it.timeMark + 1.seconds).hasPassedNow()
                    }

                    if (spamCheck.size > ABSOLUTE_MAX_PER_SECOND || spamCheck.size > ABSOLUTE_MAX_PER_MINUTE) {
                        // over the limit, time the user out
                        event.message.getAuthorAsMember()?.timeout(2.minutes)
                        event.message.author.tryDM(event.getGuild()) {
                            content = "You have been timed out for spamming in an associated server. " +
                                    "Please do not spam."
                        }
                        return@action
                    }

                    if (recentMessagesForUser.size > MAX_MESSAGES_PER_MINUTE) {
                        event.message.delete()
                        event.message.author.tryDM(event.getGuild()) {
                            content = "You have exceeded the maximum amount of messages per minute. " +
                                    "Please wait a minute before sending another message."
                        }
                    }

                    if (spamCheck.size > MAX_MESSAGES_PER_SECOND) {
                        event.message.delete()
                        event.message.author.tryDM(event.getGuild()) {
                            content = "You have exceeded the maximum amount of messages per second. " +
                                    "Please wait a second before sending another message."
                        }
                    }
                }
            }

            // finally make sure the recent messages map is cleaned up on a regular basis to not use up too much memory
            var schedulerCallback: suspend () -> Unit = {}
            schedulerCallback = {
                recentMessages.forEach { (_, messages) ->
                    // remove all messages older than 1 minute
                    messages.removeAll {
                        (it.timeMark + 60.seconds).hasPassedNow()
                    }
                }

                // reschedule the callback
                scheduler.schedule(5.minutes, pollingSeconds = 10, callback = schedulerCallback)
            }
            scheduler.schedule(5.minutes, pollingSeconds = 10, callback = schedulerCallback)
        }
        if (Module.LIMIT_MENTIONING in enabledModules) {
            event<MessageCreateEvent> {
                check { failIfNot(event.message.type == MessageType.Default) }
                check { failIf(event.message.data.authorId == kord.selfId) }
                check { failIf(event.message.author?.isBot == true) }
                check { failIf(event.guildId == null) }
                check { failIf(event.member?.roles?.any { it.id in MODERATOR_ROLES } == true) }

                action {
                    val guild = event.guildId!!

                    val mentions = event.message.mentionedUserIds + event.message.mentionedRoleIds
                    if (mentions.size > MAX_MENTIONS_PER_MESSAGE && MAX_MENTIONS_PER_MESSAGE != 0) {
                        event.message.delete()
                        event.message.author.tryDM(event.getGuild()) {
                            content = "You have exceeded the maximum amount of mentions per message. " +
                                    "Please do not mention more than $MAX_MENTIONS_PER_MESSAGE users."
                        }
                    }

                    for (snowflake in mentions) {
                        val mention = invalidMentions.get(snowflake) ?: continue
                        val referencedAuthor = event.message.messageReference?.message?.asMessageOrNull()?.author

                        if (
                            !mention.allowsReplyMentions && referencedAuthor != null && when (mention.type) {
                                ROLE -> referencedAuthor.asMember(guild).roles.any { it.id == snowflake }
                                USER -> referencedAuthor.id == snowflake
                                EVERYONE -> false // you can't reply to @everyone
                            } && referencedAuthor.id != event.message.author?.id // let the author mention themselves
                        ) {
                            val mentionName = when (mention.type) {
                                ROLE -> event.getGuild()?.getRole(snowflake)?.name ?: "Unknown role"
                                USER -> kord.getUser(snowflake)?.softMention()?.substring(1) ?: "Unknown user"
                                EVERYONE -> error("Somehow someone replied to @everyone???????????????????????????????")
                            }
                            event.message.channel.createMessage {
                                content = "Please do not reply to $mentionName with the mention option enabled."
                                allowedMentions {
                                    // leaving this empty will force the bot to not mention anyone
                                }
                            }
                        }
                    }
                }
            }
            ephemeralSlashCommand(::MentionArguments) {
                name = "change-mention-restriction"
                description = "Change the mention settings for a user or role."

                MODERATOR_ROLES.forEach(::allowRole)

                action {
                    val guild = getGuild()?.asGuild() ?: return@action
                    if (arguments.mentionable is Role && arguments.allowReplyMentions == true) {
                        throw DiscordRelayedException("You cannot allow reply mentions for a role.")
                    }

                    val mentionable = arguments.mentionable
                    val id = mentionable.id
                    val type = when (mentionable) {
                        is Role -> ROLE
                        is User -> USER
                        else -> error("Unknown mentionable type (or somehow \"@everyone\" was selected?)")
                    }

                    fun b2s(b: Boolean?) = when (b) {
                        true -> "Enabled"
                        false -> "Disabled"
                        else -> "Unchanged"
                    }

                    val mention = when (mentionable) {
                        is Role -> mentionable.mention
                        is User -> mentionable.mention
                        else -> error("Unknown mentionable type (or somehow \"@everyone\" was selected?)")
                    }

                    val invalidMention = invalidMentions.get(id)
                        ?: run {
                            val newMention = InvalidMention(id, type)
                            if (arguments.allowDirectMentions != null) {
                                newMention.allowsDirectMentions = arguments.allowDirectMentions!!
                            }
                            if (arguments.allowReplyMentions != null) {
                                newMention.allowsReplyMentions = arguments.allowReplyMentions!!
                            }

                            invalidMentions.set(newMention)

                            respond {
                                content = "Mention settings for $mention have been created: " +
                                    "Direct mentions: ${b2s(newMention.allowsDirectMentions)}, " +
                                    "Reply mentions: ${b2s(newMention.allowsReplyMentions)}"
                            }
                            return@action
                        }

                    invalidMention.allowsDirectMentions =
                        arguments.allowDirectMentions ?: invalidMention.allowsDirectMentions

                    invalidMention.allowsReplyMentions =
                        arguments.allowReplyMentions ?: invalidMention.allowsReplyMentions

                    invalidMentions.set(invalidMention)

                    respond {
                        content = "Mention settings for $mention have been updated: " +
                            "Direct mentions: ${b2s(arguments.allowDirectMentions)}, " +
                            "Reply mentions: ${b2s(arguments.allowReplyMentions)}"
                    }
                }
            }
            ephemeralSlashCommand(::MentionArguments) {
                name = "remove-mention-restriction"
                description = "Remove mention restrictions for a user or role."

                MODERATOR_ROLES.forEach(::allowRole)

                action {
                    invalidMentions.delete(arguments.mentionable.id)
                }
            }
        }
        if (Module.USER_MANAGEMENT in enabledModules) {
            ephemeralSlashCommand {
                name = "ban"
                description = "Ban a user from the server for a specified amount of time."

                MODERATOR_ROLES.forEach(::allowRole)

                ephemeralSubCommand(::ChoiceBanArguments) {
                    name = "preset"
                    description = "Ban a user from the server for a preset amount of time."

                    MODERATOR_ROLES.forEach(::allowRole)

                    action(::beanUser)
                }

                ephemeralSubCommand(::CustomBanArguments) {
                    name = "custom"
                    description = "Ban a user from the server for a custom amount of time."

                    MODERATOR_ROLES.forEach(::allowRole)

                    action(::beanUser)
                }
            }
            ephemeralSlashCommand {
                name = "timeout"
                description = "Timeout a user from the server for a specified amount of time."

                MODERATOR_ROLES.forEach(::allowRole)

                ephemeralSubCommand(::ChoiceTimeoutArguments) {
                    name = "preset"
                    description = "Timeout a user from the server for a preset amount of time."

                    MODERATOR_ROLES.forEach(::allowRole)

                    action(::timeout)
                }

                ephemeralSubCommand(::CustomTimeoutArguments) {
                    name = "custom"
                    description = "Timeout a user from the server for a custom amount of time."

                    MODERATOR_ROLES.forEach(::allowRole)

                    action(::timeout)
                }
            }
            ephemeralSlashCommand({ RequiresReason("The user to kick") }) {
                name = "kick"
                description = "Kick a user from the server."

                MODERATOR_ROLES.forEach(::allowRole)

                action {
                    val user = arguments.user

                    val guild = getGuild()!!.asGuild()
                    val member = guild.getMember(user.id)

                    try {
                        user.dm {
                            content = "You have been kicked from ${guild.name} for the following reason:\n\n" +

                                    arguments.reason
                        }

                        reportToModChannel(guild.asGuild()) {
                            description = "Kicked ${user.mention}"
                            field {
                                name = "Reason"
                                value = arguments.reason
                            }
                        }
                    } catch (e: RestRequestException) {
                        reportToModChannel(guild.asGuild()) {
                            description = "Kicked ${user.mention}"
                            field {
                                name = "Reason"
                                value = arguments.reason
                            }

                            field {
                                name = " "
                                value = "Failed to DM user."
                            }
                        }
                    }

                    member.kick(arguments.reason)

                    respond {
                        content = "Kicked ${user.softMention()} (${user.id})."
                    }
                }
            }
            ephemeralSlashCommand(::NoteArguments) {
                name = "note"
                description = "Add a note to a user, message, or both, for only moderators to see."

                MODERATOR_ROLES.forEach(::allowRole)

                action {
                    val user = arguments.user
                    val message = arguments.messageId?.let {
                        getReportingChannel(guild?.asGuild())
                            ?.asChannelOf<GuildMessageChannel>(guild!!.id)
                            ?.getMessageOrNull(it)
                    }

                    val note = arguments.note
                    val userMention = user?.mention ?: "Unknown user"
                    val currentTime = Clock.System.now().toDiscord(TimestampType.Default)

                    if (message != null) {
                        val embed = message.embeds.firstOrNull()
                        message.edit {
                            if (embed == null) {
                                embed {
                                    title = "Added notes"

                                    note(note, userMention)
                                }
                            } else {
                                embed {
                                    for (f in embed.fields) {
                                        field {
                                            name = f.name
                                            value = f.value
                                            inline = f.inline
                                        }
                                    }

                                    fields.firstOrNull { it.name == "Notes" }?.let {
                                        // field exists, append to it
                                        it.value = """
                                            $it
                                            
                                            Note added by $userMention at $currentTime:
                                            > $note
                                        """.trimIndent()
                                    } ?: note(note, userMention)
                                }
                            }
                        }
                    } else if (user != null) {
                        reportToModChannel(guild?.asGuild()) {
                            title = "User note"
                            description = """
                                This is auto-generated as a start for notes for ${user.mention}.
                                Future additions should be added with `/note` and specifying `message-id`
                            """.trimIndent()

                            note(note, userMention)
                        }
                    } else {
                        reportToModChannel {
                            title = "Note"
                            description = """
                                This is auto-generated as a start for notes with no user or previous action.
                                Future additions should be added with `/note` and specifying `message-id`
                            """.trimIndent()

                            note(note, userMention)
                        }
                    }
                }
            }

            ephemeralSlashCommand(::ActionArguments) {
                name = "id-action"
                description = "Perform an action on a user by their ID."

                MODERATOR_ROLES.forEach(::allowRole)

                action {
                    val endTime = Clock.System.now() + arguments.length.seconds
                    val discordString = endTime.toDiscord(TimestampType.Default)

                    when (arguments.action) {
                        "ban" -> {
                            val restriction = UserRestrictions(
                                arguments.user,
                                guild!!.id,
                                arguments.length != 0L,
                                endTime,
                            )

                            restriction.save()

                            guild!!.ban(arguments.user) {
                                reason = arguments.reason
                                deleteMessagesDays = arguments.banDeleteDays
                            }

                            reportToModChannel(guild?.asGuild()) {
                                title = "Banned id: ${arguments.user}"
                                field {
                                    name = "Reason"
                                    value = arguments.reason
                                }
                                field {
                                    name = "Length"
                                    value = when (arguments.length) {
                                        0L -> "Permanent"
                                        -1L -> "Removed ban"
                                        else -> discordString
                                    }
                                }
                                field {
                                    name = "User information"
                                    value = """
                                        ID: `${arguments.user}`
                                        Current name: `${event.kord.getUser(arguments.user)?.tag}`
                                        Mention: <@!${arguments.user}>
                                    """.trimIndent()
                                }
                                field {
                                    name = "Responsible moderator"
                                    value = user.mention
                                }
                            }

                            respond {
                                content = "Banned ${arguments.user} (<@!${arguments.user}>)."
                            }
                        }
                        "timeout" -> {
                            // since they're very likely not in the guild, we'll save an entry to the database
                            val restriction = UserRestrictions(
                                arguments.user,
                                guild!!.id,
                                false, // they're not banned, they're just timed out
                                endTime,
                            )

                            restriction.save()

                            reportToModChannel(guild?.asGuild()) {
                                title = "Timed out id: ${arguments.user}"
                                field {
                                    name = "Reason"
                                    value = arguments.reason
                                }
                                field {
                                    name = "Length"
                                    value = when (arguments.length) {
                                        0L -> "Permanent"
                                        -1L -> "Removed timeout"
                                        else -> discordString
                                    }
                                }
                                field {
                                    name = "User information"
                                    value = """
                                        ID: `${arguments.user}`
                                        Current name: `${event.kord.getUser(arguments.user)?.tag}`
                                        Mention: <@!${arguments.user}>
                                    """.trimIndent()
                                }
                                field {
                                    name = "Responsible moderator"
                                    value = user.mention
                                }
                            }

                            respond {
                                content = "Timed out ${arguments.user} (<@!${arguments.user}>)."
                            }
                        }
                    }
                }
            }

            val unbanUsers: suspend () -> Unit = {
                val timedOutIds = userRestrictions.getAll()
                    .filter { !it.isBanned && it.returningBanTime != null }
                    .map { it to kord.getGuild(it.guildId)?.getMemberOrNull(it._id) }
                    .filter { it.second != null }
                    .map { it.first to it.second!! }

                timedOutIds.forEach { (restriction, member) ->
                    member.edit {
                        // ok so this allows a timeout to be longer than discord's max (28 days)
                        // which means we need to use our own timeouts
                        val returnTime = restriction.returningBanTime!!
                        val currentDisabled = communicationDisabledUntil

                        val durationRemaining = returnTime - Clock.System.now()

                        @Suppress("MagicNumber") // 28 days is funky
                        if (durationRemaining.toDouble(DurationUnit.DAYS) > 28.0) {
                            if (currentDisabled == null || currentDisabled <= Clock.System.now()) {
                                // refresh the timeout
                                communicationDisabledUntil = Clock.System.now() + 28.days
                            }
                        } else {
                            if (currentDisabled == null || currentDisabled <= returnTime) {
                                // set the timeout to the remaining time
                                communicationDisabledUntil = returnTime
                            }
                        }
                    }
                }

                val bannedUsers = userRestrictions.getAll()
                    .filter { it.isBanned && it.returningBanTime!! <= Clock.System.now() }

                bannedUsers.forEach {
                    val userId = it._id
                    val guild = kord.getGuild(it.guildId)!!

                    // sanity check
                    if (guild.getBanOrNull(userId) == null) {
                        logger.warn { "User $userId was attempted to be unbanned, even though they already are" }
                        userRestrictions.remove(userId) // remove the restriction that shouldn't be there anyway
                        return@forEach
                    }

                    guild.unban(userId)
                    userRestrictions.remove(userId) // remove the restriction
                }
            }

            var repeatingTask = {}

            repeatingTask = {
                scheduler.schedule(1.seconds, callback = unbanUsers)
                @Suppress("MagicNumber")
                scheduler.schedule(5.seconds, callback = repeatingTask)
            }

            repeatingTask()
        }

        logger.info {
            "Loaded ${slashCommands.size} commands and " +
            "${slashCommands.flatMap { it.subCommands }.size} sub-commands."
        }
    }

    private suspend fun getReportingChannel(guild: Guild? = null) = modReportChannel
        ?: guild?.getModLogChannel()?.id
        ?: settings.getLadysnake()?.getConfiguredLogChannel()?.id

    private suspend inline fun reportToModChannel(
        guild: Guild? = null,
        text: String = "",
        embed: EmbedBuilder.() -> Unit = {}
    ) {
        val channel = getReportingChannel(guild) ?: return

        // weird hack to get around kmongo bug
        val msg = kord.rest.channel.createMessage(channel) {
            this.embed {
                if (text.isNotBlank()) {
                    this.description = text
                } else {
                    embed(this)
                }
            }
        }

        kord.rest.channel.editMessage(channel, msg.id) {
            this.embed {
                if (text.isNotBlank()) {
                    this.description = text
                } else {
                    embed(this)
                }

                field {
                    name = "Message ID"
                    value = msg.id.toString()
                }
            }
        }
    }

    private suspend inline fun <T> slowmode(context: EphemeralSlashCommandContext<T>)
    where T : RequiredInt, T : ChannelTargetArguments, T : Arguments {
        val channel = context.arguments.channel ?: context.channel.asChannel()
        if (channel !is GuildMessageChannel) {
            context.respond {
                content = "This command can only target a text channel which is not a thread," +
                        " and ${channel.mention} is not one."
            }
            return
        }
        val slowmode = context.arguments.i
        // a bit of a hack to attempt to bypass a bug with kmongo
        kord.rest.channel.patchChannel(channel.id, ChannelModifyPatchRequest(
            rateLimitPerUser = slowmode.optionalInt()
        ), reason = "Slowmode set by ${context.user}")
        reportToModChannel(context.guild?.asGuild()) {
            title = "Slowmode set"
            description =
                "Slowmode for ${channel.mention} was set to $slowmode seconds by ${context.user.softMention()}."
        }
    }

    private suspend fun <T> beanUser(context: EphemeralSlashCommandContext<T>)
    where T : BanArguments {
        if (context.guild == null) {
            throw DiscordRelayedException("This command can only be used in a guild.")
        }

        val user = context.arguments.user
        val member = user.asMember(context.guild!!.id)

        val reason = context.arguments.reason
        val length = context.arguments.length

        val restriction = UserRestrictions(
            member.id,
            context.guild!!.id,
            length >= 0,
            // Instant.DISTANT_FUTURE is January 1, 100,000 which truly is quite distant in the future
            if (length > 0) Clock.System.now() + length.seconds else Instant.DISTANT_FUTURE,
        )

        val returnTime = restriction.returningBanTime!!.toDiscord(TimestampType.ShortDate)

        try {
            user.dm {
                content = "You have been banned from ${context.guild!!.asGuild().name} " +
                        "until $returnTime for the following reason:\n\n" +

                        context.arguments.reason
            }

            reportToModChannel(context.guild?.asGuild()) {
                description = "Banned ${user.mention}"
                field {
                    name = "Reason"
                    value = context.arguments.reason
                }
                field {
                    name = "Length"
                    value = when (length) {
                        -1L -> "Permanent"
                        0L -> "Unbanned"
                        else -> "$length seconds (until $returnTime)"
                    }
                }
                field {
                    name = "Responsible moderator"
                    value = context.user.mention
                }
            }
        } catch (e: RestRequestException) {
            reportToModChannel(context.guild?.asGuild()) {
                description = "Banned ${user.mention}"
                field {
                    name = "Reason"
                    value = context.arguments.reason
                }
                field {
                    name = "Length"
                    value = when (length) {
                        -1L -> "Permanent"
                        0L -> "Unbanned"
                        else -> "$length seconds (until $returnTime)"
                    }
                }
                field {
                    name = "Responsible moderator"
                    value = context.user.mention
                }

                field {
                    name = " "
                    value = "Failed to DM user."
                }
            }
        }

        if (restriction.isBanned) {
            // ban the user (the restriction just was created)
            member.ban {
                this.reason = reason
                deleteMessagesDays = context.arguments.daysToDelete
            }
        }

        // add the restriction to the database
        userRestrictions.set(restriction)

        context.respond {
            content = "Banned ${user.softMention()} (${user.id})."
        }
    }

    private suspend fun <T : TimeoutArguments> timeout(context: EphemeralSlashCommandContext<T>) {
        val user = context.arguments.user
        val member = user.asMember(context.guild!!.id)

        val reason = context.arguments.reason
        val length = context.arguments.l
        val endTime = Clock.System.now() + length.seconds

        // using discord's *NEW* built-in timeout functionality
        member.edit {
            communicationDisabledUntil = if (length > 0) {
                Clock.System.now() + length.seconds
            } else null

            this.reason = reason
        }

        val returnTime = endTime.toDiscord(TimestampType.ShortDate)

        try {
            user.dm {
                content = "You have been timed out from ${context.guild!!.asGuild().name} " +
                        "until $returnTime for the following reason:\n\n" +

                        context.arguments.reason
            }

            reportToModChannel(context.guild?.asGuild()) {
                description = "Timed out ${user.mention}"
                field {
                    name = "Reason"
                    value = context.arguments.reason
                }
                field {
                    name = "Length"
                    value = when (length) {
                        -1L -> "Permanent"
                        0L -> "Unbanned"
                        else -> "$length seconds (until $returnTime)"
                    }
                }
                field {
                    name = "Responsible moderator"
                    value = context.user.mention
                }
            }
        } catch (e: RestRequestException) {
            reportToModChannel(context.guild?.asGuild()) {
                description = "Timed out ${user.mention}"
                field {
                    name = "Reason"
                    value = context.arguments.reason
                }
                field {
                    name = "Length"
                    value = when (length) {
                        -1L -> "Permanent"
                        0L -> "Unbanned"
                        else -> "$length seconds (until $returnTime)"
                    }
                }
                field {
                    name = "Responsible moderator"
                    value = context.user.mention
                }

                field {
                    name = " "
                    value = "Failed to DM user."
                }
            }
        }

        context.respond {
            content = "Timed out ${user.softMention()} (${user.id})."
        }
    }

    private suspend fun User?.tryDM(guild: Guild? = null, builder: UserMessageCreateBuilder.() -> Unit) {
        if (this != null) {
            try {
                this.getDmChannel().createMessage(builder)
            } catch (e: RestRequestException) {
                reportToModChannel(guild) {
                    title = "DM failed"
                    description = "Failed to send DM to $mention. (the user likely has DMs disabled)"
                }
            }
        } else {
            reportToModChannel(guild) {
                title = "DM failed"
                description = "Failed to send DM to null user."
            }
        }
    }

    private fun EmbedBuilder.note(noteText: String, modMention: String) {
        field {
            name = "Notes"
            value = """
                Note added by $modMention at ${Clock.System.now().toDiscord(TimestampType.Default)}:
                > $noteText
            """.trimIndent()
        }
    }

    enum class Module {
        PURGE,
        USER_MANAGEMENT,
        LIMIT_SPAM,
        BAN_SHARING, // TODO implement
        CHANNEL_SLOWMODE,
        LIMIT_MENTIONING,
        ;

        companion object {
            val allSupported = values()
                .filterNot { it in listOf(
                    BAN_SHARING // the only one that's not supported yet
                ) }
        }
    }

    interface RequiredInt {
        val i: Int
    }

    interface RequiredLong {
        val l: Long
    }

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

    class ChannelSlowModeArguments : Arguments(), ChannelTargetArguments, RequiredInt {
        override val i by int {
            name = "seconds"
            description = "The minimum time to wait between messages in seconds"

            validate {
                failIf("You must specify a positive length!") { value < 0 }
            }
        }

        override val channel by optionalChannel {
            name = "channel"
            description = "The channel to set slowmode for (current if omitted)"
        }
    }

    class ChannelDefaultSlowModeArguments : Arguments(), ChannelTargetArguments, RequiredInt {
        @Suppress("MagicNumber")
        override val i by intChoice {
            name = "seconds"
            description = "The minimum time to wait between messages in seconds"

            choices["1 second"] = 1
            choices["5 seconds"] = 5
            choices["10 seconds"] = 10
            choices["30 seconds"] = 30
            choices["1 minute"] = 60
            choices["5 minutes"] = 300
            choices["10 minutes"] = 600
            choices["30 minutes"] = 1_800
            choices["1 hour"] = 3_600
            choices["2 hours"] = 7_200
            choices["3 hours"] = 10_800
            choices["6 hours"] = 21_600
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

    abstract class BanArguments : RequiresReason("The user to ban") {
        abstract val length: Long

        val daysToDelete by banDeleteDaySelector()
    }

    class CustomBanArguments : BanArguments() {
        @Suppress("MagicNumber")
        override val length by defaultingLong {
            name = "length"
            description = "The length of the ban in seconds, 0 for indefinite, or -1 to end (default 1 month)"
            defaultValue = 2_592_000
        }
    }

    class ChoiceBanArguments : BanArguments() {
        @Suppress("MagicNumber")
        override val length by defaultingNumberChoice {
            name = "length"
            description = "The length of the ban in seconds, 0 for indefinite, or -1 to end (default 1 month)"
            defaultValue = 2_592_000

            choices["1 minute"] = 60
            choices["5 minutes"] = 300
            choices["10 minutes"] = 600
            choices["30 minutes"] = 1_800
            choices["1 hour"] = 3_600
            choices["2 hours"] = 7_200
            choices["3 hours"] = 10_800
            choices["6 hours"] = 21_600
            choices["1 day"] = 86_400
            choices["2 days"] = 172_800
            choices["3 days"] = 259_200
            choices["1 week"] = 604_800
            choices["2 weeks"] = 1_209_600
            choices["3 weeks"] = 1_814_400
            choices["1 month"] = 2_592_000
            choices["2 months"] = 5_184_000
            choices["3 months"] = 7_168_000
            choices["6 months"] = 15_552_000
            choices["1 year"] = 31_556_952
            // up to 4 more entries can be added here
            choices["forever"] = 0
            choices["unbanned"] = -1
        }
    }

    abstract class TimeoutArguments : RequiresReason("The user to timeout"), RequiredLong

    class ChoiceTimeoutArguments : TimeoutArguments() {
        @Suppress("MagicNumber")
        override val l by defaultingNumberChoice {
            name = "length"
            description = "The length of the timeout (default 5 minutes)"
            defaultValue = 300

            choices["60 seconds"] = 60
            choices["5 minutes"] = 300
            choices["10 minutes"] = 600
            choices["1 hour"] = 3_600
            choices["1 day"] = 86_400
            choices["1 week"] = 604_800
            choices["stop"] = -1
        }
    }

    class CustomTimeoutArguments : TimeoutArguments() {
        @Suppress("MagicNumber")
        override val l by defaultingLong {
            name = "length"
            description = "The length of the timeout in seconds (default 5 minutes, max 28 days, -1 to end)"
            defaultValue = 300

            validate {
                failIfNot("length must be between 0 and 28 days") {
                    value in -1..28_719
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
            choices["unban"] = "unban"
        }

        val reason by string {
            name = "reason"
            description = "The reason for the action"
        }

        @Suppress("MagicNumber")
        val length by defaultingNumberChoice {
            name = "length"
            description = "The length of the action (default 1 month)"
            defaultValue = 2_592_000

            choices["1 minute"] = 60
            choices["5 minutes"] = 300
            choices["10 minutes"] = 600
            choices["30 minutes"] = 1_800
            choices["1 hour"] = 3_600
            choices["2 hours"] = 7_200
            choices["3 hours"] = 10_800
            choices["6 hours"] = 21_600
            choices["1 day"] = 86_400
            choices["2 days"] = 172_800
            choices["3 days"] = 259_200
            choices["1 week"] = 604_800
            choices["2 weeks"] = 1_209_600
            choices["3 weeks"] = 1_814_400
            choices["1 month"] = 2_592_000
            choices["2 months"] = 5_184_000
            choices["3 months"] = 7_168_000
            choices["6 months"] = 15_552_000
            choices["1 year"] = 31_556_952
            // up to 4 more entries can be added here
            choices["forever"] = 0
            choices["unbanned"] = -1
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

    companion object {
        @Suppress("MagicNumber")
        internal fun Arguments.banDeleteDaySelector() = defaultingIntChoice {
            name = "delete-days"
            description = "The amount of days to delete messages from the user's history"
            defaultValue = 0
            choices = buildMap {
                for (i in 0..6) {
                    put("$i days", i)
                }

                // two special cases for displayed names
                // 1 day
                put("1 day", 1)
                // 7 days
                put("1 week", 7)
            }.toMutableMap() // kordex requires a mutable map
        }
    }
}
