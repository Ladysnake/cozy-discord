package org.quiltmc.community.modes.quilt.extensions.moderation

import com.kotlindiscord.kord.extensions.DiscordRelayedException
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
import dev.kord.core.behavior.ban
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.entity.KordEntity
import dev.kord.core.entity.Member
import dev.kord.core.entity.Role
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.Channel
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.UserMessageCreateBuilder
import dev.kord.rest.builder.message.create.allowedMentions
import dev.kord.rest.builder.message.create.embed
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
                            reportToModChannel {
                                title = "Message purged"
                                description = message.content
                                author {
                                    name = message.author?.username
                                    icon = message.author?.avatar?.url
                                }
                                footer {
                                    text = "Purged by ${user.softMention()}"
                                }
                            }
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
                        override val channel by optionalChannel(
                            "channel",
                            "The channel to disable slowmode in"
                        )
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
                    if (recentMessagesForUser.size > MAX_MESSAGES_PER_MINUTE) {
                        event.message.delete()
                        event.message.author.tryDM {
                            content = "You have exceeded the maximum amount of messages per minute. " +
                                    "Please wait a minute before sending another message."
                        }
                    }

                    // also check for spam that happened in a second
                    val spamCheck = recentMessagesForUser.filterNot {
                        (it.timeMark + 1.seconds).hasPassedNow()
                    }
                    if (spamCheck.size > MAX_MESSAGES_PER_SECOND) {
                        event.message.delete()
                        event.message.author.tryDM {
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
                        event.message.author.tryDM {
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
                    if (arguments.mentionable is Role && arguments.allowReplyMentions) {
                        throw DiscordRelayedException("You cannot allow reply mentions for a role.")
                    }

                    val mentionable = arguments.mentionable
                    val id = mentionable.id
                    val type = when (mentionable) {
                        is Role -> ROLE
                        is User -> USER
                        else -> error("Unknown mentionable type (or somehow \"@everyone\" was selected?)")
                    }

                    val invalidMention = invalidMentions.get(id)
                        ?: InvalidMention(id, type)

                    invalidMention.allowsDirectMentions = arguments.allowDirectMentions
                    invalidMention.allowsReplyMentions = arguments.allowReplyMentions

                    invalidMentions.set(invalidMention)
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

                        reportToModChannel {
                            description = "Kicked ${user.mention}"
                            field {
                                name = "Reason"
                                value = arguments.reason
                            }
                        }
                    } catch (e: RestRequestException) {
                        reportToModChannel {
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

                            reportToModChannel {
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

                            reportToModChannel {
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

            var unbanUsers: suspend () -> Unit = {}

            val unbanUsersSchedulerTask: suspend () -> Unit = {
                scheduler.schedule(1.seconds, callback = unbanUsers)
            }

            unbanUsers = {
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

                scheduler.schedule(4.seconds, callback = unbanUsersSchedulerTask)
            }

            unbanUsers()
        }

        logger.info {
            "Loaded ${slashCommands.size} commands and " +
            "${slashCommands.flatMap { it.subCommands }.size} sub-commands."
        }
    }

    private suspend inline fun reportToModChannel(
        text: String = "",
        embed: EmbedBuilder.() -> Unit = {}
    ) {
        val channel = modReportChannel
            ?: settings.getLadysnake()?.getConfiguredLogChannel()?.id
            ?: return

        // weird hack to get around kmongo bug
        kord.rest.channel.createMessage(channel) {
            if (text.isNotEmpty()) {
                content = text
            } else {
                this.embed(embed)
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
        reportToModChannel {
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

            reportToModChannel {
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
            reportToModChannel {
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

            reportToModChannel {
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
            reportToModChannel {
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

    private suspend fun User?.tryDM(builder: UserMessageCreateBuilder.() -> Unit) {
        if (this != null) {
            try {
                this.getDmChannel().createMessage(builder)
            } catch (e: RestRequestException) {
                reportToModChannel {
                    title = "DM failed"
                    description = "Failed to send DM to $mention. (the user likely has DMs disabled)"
                }
            }
        } else {
            reportToModChannel {
                title = "DM failed"
                description = "Failed to send DM to null user."
            }
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
        val user by user(
            "user",
            mentionableDesc,
            validator = validator
        )
    }

    class PurgeArguments : Arguments(), ChannelTargetArguments {
        val amount by int("amount", "The amount of messages to purge")
        val user by optionalUser("user", "The user to purge messages from (all if omitted)")
        override val channel by optionalChannel("channel", "The channel to purge messages from (current if omitted)")
    }

    class ChannelSlowModeArguments : Arguments(), ChannelTargetArguments, RequiredInt {
        override val i by defaultingInt("rate", "The minimum time to wait between messages in seconds", 1)

        override val channel by optionalChannel("channel", "The channel to set slowmode for")
    }

    class ChannelDefaultSlowModeArguments : Arguments(), ChannelTargetArguments, RequiredInt {
        @Suppress("MagicNumber")
        override val i by intChoice("rate", "The time between messages to wait", mapOf(
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
        ))

        override val channel by optionalChannel("channel", "The channel to set slowmode for")
    }

    class MentionArguments : Arguments() {
        val mentionable by mentionable(
            "entity",
            "The role or user to warn people about when mentioning them"
        )
        val allowDirectMentions by defaultingBoolean(
            "allow-direct-mentions",
            "Whether to allow the role or user to be mentioned directly in a message",
            false
        )
        val allowReplyMentions by defaultingBoolean(
            "allow-reply-mentions",
            "Whether to allow the user to be mentioned in a reply to a message",
            false
        )
    }

    open class RequiresReason(
        mentionableDesc: String,
        validator: Validator<KordEntity> = null
    ) : RequiredUser(mentionableDesc, validator) {
        val reason by string("reason", "The reason for the action")
    }

    abstract class BanArguments : RequiresReason("The user to ban") {
        abstract val length: Long

        val daysToDelete by banDeleteDaySelector()
    }

    class CustomBanArguments : BanArguments() {
        @Suppress("MagicNumber")
        override val length by defaultingLong(
            "length",
            "The length of the ban in seconds, 0 for indefinite, or -1 to end (default 1 month)",
            2_592_000
        )
    }

    class ChoiceBanArguments : BanArguments() {
        @Suppress("MagicNumber")
        override val length by defaultingNumberChoice(
            "length",
            "The length of the ban (default 1 month)",
            2_592_000,
            choices = mapOf(
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
                // up to 4 more entries can be added here
                "forever" to 0,
                "unbanned" to -1
            )
        )
    }

    abstract class TimeoutArguments : RequiresReason("The user to timeout"), RequiredLong

    class ChoiceTimeoutArguments : TimeoutArguments() {
        @Suppress("MagicNumber")
        override val l by defaultingNumberChoice(
            "length",
            "The length of the timeout (default 5 minutes)",
            300,
            choices = mapOf(
                "60 seconds" to 60,
                "5 minutes" to 300,
                "10 minutes" to 600,
                "1 hour" to 3_600,
                "1 day" to 86_400,
                "1 week" to 604_800,
                "stop" to -1
            )
        )
    }

    class CustomTimeoutArguments : TimeoutArguments() {
        @Suppress("MagicNumber")
        override val l by defaultingLong(
            "length",
            "The length of the timeout in seconds (default 5 minutes, max 28 days, -1 to end)",
            300
        ) { _, value ->
            if (value < 0 || value > 28_800) {
                throw DiscordRelayedException("Length must be between 0 and 28800 seconds")
            }
        }
    }

    class ActionArguments : Arguments() {
        val user by snowflake("user", "The user to perform the action on, as their user ID")
        val action by stringChoice(
            "action",
            "The action to take on the user",
            mapOf(
                "ban" to "ban",
                "timeout" to "timeout",
            )
        )
        val reason by string("reason", "The reason for the action")

        @Suppress("MagicNumber")
        val length by defaultingNumberChoice(
            "length",
            "The length of the action (default 1 month)",
            2_592_000,
            choices = mapOf(
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
                // up to 4 more entries can be added here
                "forever" to 0,
                "unbanned" to -1
            )
        )

        val banDeleteDays by banDeleteDaySelector()
    }

    companion object {
        @Suppress("MagicNumber")
        internal fun Arguments.banDeleteDaySelector() = defaultingIntChoice(
            "delete-days",
            "The amount of days to delete messages from the user's history",
            0,
            choices = buildMap {
                for (i in 0..6) {
                    put("$i days", i)
                }

                // two special cases for displayed names
                // 1 day
                put("1 day", 1)
                // 7 days
                put("1 week", 7)
            }
        )
    }
}
