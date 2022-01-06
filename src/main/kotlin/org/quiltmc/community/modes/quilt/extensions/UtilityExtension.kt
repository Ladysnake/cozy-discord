@file:OptIn(ExperimentalTime::class, KordPreview::class, InternalCoroutinesApi::class)

@file:Suppress("MagicNumber")  // Yep. I'm done.

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.*
import com.kotlindiscord.kord.extensions.checks.*
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.application.slash.publicSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralMessageCommand
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.types.edit
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.types.respondEphemeral
import com.kotlindiscord.kord.extensions.utils.deleteIgnoringNotFound
import com.kotlindiscord.kord.extensions.utils.dm
import dev.kord.common.annotation.KordPreview
import dev.kord.common.entity.*
import dev.kord.core.behavior.channel.*
import dev.kord.core.behavior.channel.threads.edit
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Guild
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.thread.ThreadChannel
import dev.kord.core.event.channel.thread.TextChannelThreadCreateEvent
import dev.kord.core.event.channel.thread.ThreadUpdateEvent
import dev.kord.core.event.guild.GuildCreateEvent
import dev.kord.core.event.guild.GuildUpdateEvent
import dev.kord.core.event.message.MessageCreateEvent
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.koin.core.component.inject
import org.quiltmc.community.*
import org.quiltmc.community.database.collections.OwnedThreadCollection
import org.quiltmc.community.database.collections.UserFlagsCollection
import org.quiltmc.community.database.entities.OwnedThread
import org.quiltmc.community.database.entities.UserFlags
import org.quiltmc.community.database.getSettings
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

val SPEAKING_PERMISSIONS: Array<Permission> = arrayOf(
    Permission.SendMessages,
    Permission.AddReactions,
    Permission.CreatePublicThreads,
    Permission.CreatePrivateThreads,
    Permission.SendMessagesInThreads,
)

val THREAD_CHANNEL_TYPES: Array<ChannelType> = arrayOf(
    ChannelType.PublicGuildThread,
    ChannelType.PublicNewsThread,
    ChannelType.PrivateThread,
)

val TEXT_CHANNEL_TYPES: Array<ChannelType> = arrayOf(
    ChannelType.GuildText,
    ChannelType.GuildNews,
)

val DELETE_DELAY = 10.seconds
val MESSAGE_EDIT_DELAY = 3.seconds

class UtilityExtension : Extension() {
    override val name: String = "utility"

    private val logger = KotlinLogging.logger { }
    private val threads: OwnedThreadCollection by inject()

    private val guildCache: MutableMap<Snowflake, Guild> = mutableMapOf()
    private val userFlags: UserFlagsCollection by inject()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "    "

        encodeDefaults = false
    }

    override suspend fun setup() {
        event<MessageCreateEvent> {
            check { inLadysnakeGuild() }
            check { isNotBot() }
            check { channelType(ChannelType.GuildNews) }
            check { failIf(event.message.author == null) }

            action {
                val flags = userFlags.get(event.message.author!!.id) ?: UserFlags(event.message.author!!.id)

                if (flags.autoPublish) {
                    event.message.publish()
                }
            }
        }

        event<MessageCreateEvent> {
            check { failIf { event.message.type != MessageType.ChannelPinnedMessage } }
//            check { failIf { event.message.data.authorId != event.kord.selfId } }

            action {
                delay(DELETE_DELAY)

                event.message.deleteIgnoringNotFound()
            }
        }

        event<MessageCreateEvent> {
            check { failIf { event.message.type != MessageType.ThreadCreated } }

            action {
                // discord is so very concern
                // i need to get the first message in the thread to check whether i should delete the creation message

                // oh did i mention the discord api puts the thread channel id as part of the "message reference"
                val thread = event.message.messageReference?.channel?.asChannelOf<ThreadChannel>()
                    ?: return@action

                val firstMessage = thread.messages.firstOrNull() ?: return@action

                if (firstMessage.messageReference != null && firstMessage.messageReference!!.message != null) {
                    delay(DELETE_DELAY)

                    event.message.delete("Removing thread creation message of a thread with a parent message")
                }
            }
        }

        event<TextChannelThreadCreateEvent> {
            check { inLadysnakeGuild() }
            check { failIf(event.channel.ownerId == kord.selfId) }
            check { failIf(event.channel.member != null) }  // We only want thread creation, not join

            action {
                val owner = event.channel.owner.asUser()

                logger.info { "Thread created by ${owner.tag}" }

                val role = when (event.channel.guildId) {
                    LADYSNAKE_GUILD -> event.channel.guild.getRole(LADYSNAKE_MODERATOR_ROLE)
                    YOUTUBE_GUILD -> event.channel.guild.getRole(YOUTUBE_MODERATOR_ROLE)

                    else -> return@action
                }

                val message = event.channel.createMessage {
                    content = "Oh hey, that's a nice thread you've got there! Let me just get the mods in on this " +
                            "sweet discussion..."
                }

                event.channel.withTyping {
                    delay(3.seconds)
                }

                message.edit {
                    content = "Hey, ${role.mention}, you've gotta check this thread out!"
                }

                event.channel.withTyping {
                    delay(3.seconds)
                }

                message.edit {
                    content = "Welcome to your new thread, ${owner.mention}! This message is at the " +
                            "start of the thread. Remember, you're welcome to use the `/thread` commands to manage " +
                            "your thread as needed."
                }

                message.pin("First message in the thread.")
            }
        }

        event<ThreadUpdateEvent> {
            action {
                val channel = event.channel
                val ownedThread = threads.get(channel)

                if (channel.isArchived && ownedThread != null && ownedThread.preventArchiving) {
                    channel.edit {
                        archived = false
                        reason = "Preventing thread from being archived."
                    }
                }
            }
        }

        event<GuildCreateEvent> {
            action {
                guildCache[event.guild.id] = event.guild
            }
        }

        event<GuildUpdateEvent> {
            action {
                val new = event.guild
                val old = guildCache[new.id] ?: return@action

                if (old.features != new.features) {
                    val added = new.features - old.features
                    val removed = old.features - new.features

                    if (added.isNotEmpty() && removed.isNotEmpty()) return@action

                    new.getModLogChannel()?.createEmbed {
                        color = DISCORD_FUCHSIA
                        timestamp = Clock.System.now()
                        title = "Guild Flags Updated"

                        description = ""

                        if (added.isNotEmpty()) {
                            description += "**Added:** " + added.joinToString { "`${it.value}`" } + "\n"
                        }

                        if (removed.isNotEmpty()) {
                            description += "**Removed:** " + removed.joinToString { "`${it.value}`" }
                        }
                    }
                }
            }
        }

        GUILDS.forEach { guildId ->
            ephemeralMessageCommand {
                name = "Raw JSON"

                guild(guildId)

                check { hasBaseModeratorRole() }

                action {
                    val messages = targetMessages.map { it.data }
                    val data = json.encodeToString(messages)

                    respond {
                        content = "Raw message data attached below."

                        addFile("message.json", data.byteInputStream())
                    }
                }
            }

            ephemeralMessageCommand {
                name = "Pin in thread"

                guild(guildId)

                check { isInThread() }

                action {
                    val channel = channel.asChannelOf<ThreadChannel>()
                    val member = user.asMember(guild!!.id)
                    val roles = member.roles.toList().map { it.id }

                    if (MODERATOR_ROLES.any { it in roles }) {
                        targetMessages.forEach { it.pin("Pinned by ${member.tag}") }
                        edit { content = "Messages pinned." }

                        return@action
                    }

                    if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
                        respond { content = "**Error:** This is not your thread." }

                        return@action
                    }

                    targetMessages.forEach { it.pin("Pinned by ${member.tag}") }

                    edit { content = "Messages pinned." }
                }
            }

            ephemeralMessageCommand {
                name = "Unpin in thread"

                guild(guildId)

                check { isInThread() }

                action {
                    val channel = channel.asChannelOf<ThreadChannel>()
                    val member = user.asMember(guild!!.id)
                    val roles = member.roles.toList().map { it.id }

                    if (MODERATOR_ROLES.any { it in roles }) {
                        targetMessages.forEach { it.unpin("Unpinned by ${member.tag}") }
                        edit { content = "Messages unpinned." }

                        return@action
                    }

                    if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
                        respond { content = "**Error:** This is not your thread." }

                        return@action
                    }

                    targetMessages.forEach { it.unpin("Unpinned by ${member.tag}") }

                    edit { content = "Messages unpinned." }
                }
            }

            ephemeralSlashCommand {
                name = "thread"
                description = "Thread management commands"

                guild(guildId)

                check { isInThread() }

                publicSubCommand {
                    name = "backup"
                    description = "Get all messages in the current thread, saving them into a Markdown file."

                    guild(guildId)

                    check { hasBaseModeratorRole() }

                    action {
                        val thread = channel.asChannelOfOrNull<ThreadChannel>()

                        if (thread == null) {
                            respondEphemeral {
                                content = "**Error:** This channel isn't a thread!"
                            }

                            return@action
                        }

                        val messageBuilder = StringBuilder()
                        val formatter = DateTimeFormatter.ofPattern("dd LL, yyyy -  kk:mm:ss")

                        if (thread.lastMessageId == null) {
                            respondEphemeral {
                                content = "**Error:** This thread has no messages!"
                            }

                            return@action
                        }

                        val messages = thread.getMessagesBefore(thread.lastMessageId!!)
                        val lastMessage = thread.getLastMessage()!!

                        val parent = thread.parent.asChannel()

                        messageBuilder.append("# Thread: ${thread.name}\n\n")
                        messageBuilder.append("* **ID:** `${thread.id.value}`\n")
                        messageBuilder.append("* **Parent:** #${parent.name} (`${parent.id.value}`)\n\n")

                        val messageStrings: MutableList<String> = mutableListOf()

                        messages.collect { msg ->
                            val author = msg.author
                            val builder = StringBuilder()
                            val timestamp = formatter.format(
                                msg.id.timestamp.toLocalDateTime(TimeZone.UTC).toJavaLocalDateTime()
                            )

                            if (msg.content.isNotEmpty() || msg.attachments.isNotEmpty()) {
                                val authorName = author?.tag ?: msg.data.author.username

                                this@UtilityExtension.logger.debug { "\nAuthor name: `$authorName`\n${msg.content}\n" }

                                if (msg.type == MessageType.ApplicationCommand) {
                                    builder.append("🖥️ ")
                                } else if (author == null) {
                                    builder.append("🌐 ")
                                } else if (author.isBot) {
                                    builder.append("🤖 ")
                                } else {
                                    builder.append("💬 ")
                                }

                                builder.append("**$authorName** at $timestamp (UTC)\n\n")

                                if (msg.content.isNotEmpty()) {
                                    builder.append(msg.content.lines().joinToString("\n") { line -> "> $line" })
                                    builder.append("\n\n")
                                }

                                if (msg.attachments.isNotEmpty()) {
                                    msg.attachments.forEach { att ->
                                        builder.append("* 📄 [${att.filename}](${att.url})\n")
                                    }

                                    builder.append("\n")
                                }

                                messageStrings.add(builder.toString())
                            }
                        }

                        messageStrings.reverse()

                        lastMessage.let { msg ->
                            val author = msg.author
                            val builder = StringBuilder()
                            val timestamp = formatter.format(
                                msg.id.timestamp.toLocalDateTime(TimeZone.UTC).toJavaLocalDateTime()
                            )

                            if (msg.content.isNotEmpty() || msg.attachments.isNotEmpty()) {
                                val authorName = author?.tag ?: msg.data.author.username

                                if (msg.type == MessageType.ApplicationCommand) {
                                    builder.append("🖥️ ")
                                } else if (author == null) {
                                    builder.append("🌐 ")
                                } else if (author.isBot) {
                                    builder.append("🤖 ")
                                } else {
                                    builder.append("💬 ")
                                }

                                builder.append("**$authorName** at $timestamp (UTC)\n\n")

                                if (msg.content.isNotEmpty()) {
                                    builder.append(msg.content.lines().joinToString("\n") { line -> "> $line" })
                                    builder.append("\n\n")
                                }

                                if (msg.attachments.isNotEmpty()) {
                                    msg.attachments.forEach { att ->
                                        builder.append("* 📄 [${att.filename}](${att.url})\n")
                                    }

                                    builder.append("\n")
                                }

                                messageStrings.add(builder.toString())
                            }
                        }

                        messageStrings.forEach(messageBuilder::append)

                        respond {
                            content = "**Thread backup created by ${user.mention}.**"

                            addFile("thread.md", messageBuilder.toString().byteInputStream())
                        }
                    }
                }

                ephemeralSubCommand(::RenameArguments) {
                    name = "rename"
                    description = "Rename the current thread, if you have permission"

                    action {
                        val channel = channel.asChannelOf<ThreadChannel>()
                        val member = user.asMember(guild!!.id)
                        val roles = member.roles.toList().map { it.id }

                        if (MODERATOR_ROLES.any { it in roles }) {
                            channel.edit {
                                name = arguments.name

                                reason = "Renamed by ${member.tag}"
                            }

                            edit { content = "Thread renamed." }

                            return@action
                        }

                        if ((channel.ownerId != user.id && threads.isOwner(channel, user) != true)) {
                            edit { content = "**Error:** This is not your thread." }

                            return@action
                        }

                        channel.edit {
                            name = arguments.name

                            reason = "Renamed by ${member.tag}"
                        }

                        edit { content = "Thread renamed." }
                    }
                }

                ephemeralSubCommand(::ArchiveArguments) {
                    name = "archive"
                    description = "Archive the current thread, if you have permission"

                    action {
                        val channel = channel.asChannelOf<ThreadChannel>()
                        val member = user.asMember(guild!!.id)
                        val roles = member.roles.toList().map { it.id }
                        val ownedThread = threads.get(channel)

                        if (MODERATOR_ROLES.any { it in roles }) {
                            if (ownedThread != null) {
                                ownedThread.preventArchiving = false
                                threads.set(ownedThread)
                            }

                            channel.edit {
                                this.archived = true
                                this.locked = arguments.lock

                                reason = "Archived by ${user.asUser().tag}"
                            }

                            edit {
                                content = "Thread archived"

                                if (arguments.lock) {
                                    content += " and locked"
                                }

                                content += "."
                            }

                            return@action
                        }

                        if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
                            edit { content = "This is not your thread." }

                            return@action
                        }

                        if (channel.isArchived) {
                            edit { content = "**Error:** This channel is already archived." }

                            return@action
                        }

                        if (arguments.lock) {
                            edit { content = "**Error:** Only members of the community team may lock threads." }

                            return@action
                        }

                        if (ownedThread != null && ownedThread.preventArchiving) {
                            edit {
                                content = "**Error:** This thread can only be archived by a moderator."
                            }

                            return@action
                        }

                        channel.edit {
                            archived = true

                            reason = "Archived by ${user.asUser().tag}"
                        }

                        edit { content = "Thread archived." }
                    }
                }

                ephemeralSubCommand(::PinMessageArguments) {
                    name = "pin"
                    description = "Pin a message in this thread, if you have permission"

                    action {
                        val channel = channel.asChannelOf<ThreadChannel>()
                        val member = user.asMember(guild!!.id)
                        val roles = member.roles.toList().map { it.id }

                        if (arguments.message.channelId != channel.id) {
                            edit {
                                content = "**Error:** You may only pin a message in the current thread."
                            }

                            return@action
                        }

                        if (MODERATOR_ROLES.any { it in roles }) {
                            arguments.message.pin("Pinned by ${member.tag}")
                            edit { content = "Message pinned." }

                            return@action
                        }

                        if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
                            edit { content = "**Error:** This is not your thread." }

                            return@action
                        }

                        arguments.message.pin("Pinned by ${member.tag}")

                        edit { content = "Message pinned." }
                    }
                }

                ephemeralSubCommand(::PinMessageArguments) {
                    name = "unpin"
                    description = "Unpin a message in this thread, if you have permission"

                    action {
                        val channel = channel.asChannelOf<ThreadChannel>()
                        val member = user.asMember(guild!!.id)
                        val roles = member.roles.toList().map { it.id }

                        if (arguments.message.channelId != channel.id) {
                            edit {
                                content = "**Error:** You may only pin a message in the current thread."
                            }

                            return@action
                        }

                        if (MODERATOR_ROLES.any { it in roles }) {
                            arguments.message.unpin("Unpinned by ${member.tag}")
                            edit { content = "Message unpinned." }

                            return@action
                        }

                        if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
                            edit { content = "**Error:** This is not your thread." }

                            return@action
                        }

                        arguments.message.unpin("Unpinned by ${member.tag}")

                        edit { content = "Message unpinned." }
                    }
                }

                ephemeralSubCommand {
                    name = "prevent-archiving"
                    description = "Prevent the current thread from archiving, if you have permission"

                    guild(guildId)

                    check {
                        hasBaseModeratorRole()
                        isInThread()
                    }

                    action {
                        val channel = channel.asChannelOf<ThreadChannel>()
                        val member = user.asMember(guild!!.id)

                        if (channel.isArchived) {
                            channel.edit {
                                archived = false
                                reason = "`/thread prevent-archiving` run by ${member.tag}"
                            }
                        }

                        val thread = threads.get(channel)

                        if (thread != null) {
                            if (thread.preventArchiving) {
                                edit {
                                    content = "I'm already stopping this thread from being archived."
                                }

                                return@action
                            }

                            thread.preventArchiving = true
                            threads.set(thread)
                        } else {
                            threads.set(
                                OwnedThread(
                                    channel.id,
                                    channel.owner.id,
                                    channel.guild.id,
                                    true
                                )
                            )
                        }

                        edit { content = "Thread will no longer be archived." }
                    }
                }
            }

            ephemeralSlashCommand(::SayArguments) {
                name = "say"
                description = "Send a message."

                guild(guildId)

                check { hasBaseModeratorRole() }

                action {
                    val targetChannel = (arguments.target ?: channel.asChannel()).asChannelOf<GuildMessageChannel>()

                    targetChannel.createMessage(arguments.message)

                    guild?.asGuild()?.getCozyLogChannel()?.createEmbed {
                        title = "/say command used"
                        description = arguments.message

                        field {
                            inline = true
                            name = "Channel"
                            value = targetChannel.mention
                        }

                        field {
                            inline = true
                            name = "User"
                            value = user.mention
                        }
                    }

                    edit { content = "Done!" }
                }
            }

            ephemeralSlashCommand(::MuteRoleArguments) {
                name = "fix-mute-role"
                description = "Fix the permissions for the mute role on this server."

                guild(guildId)

                check { hasPermission(Permission.Administrator) }

                action {
                    val role = arguments.role ?: guild?.asGuild()?.roles?.firstOrNull { it.name.lowercase() == "muted" }

                    if (role == null) {
                        respond {
                            content =
                                "Unable to find a role named `Muted` - double-check the list of roles, or provide " +
                                        "one as an argument."
                        }
                        return@action
                    }

                    var channelsUpdated = 0

                    for (channel in guild!!.channels.toList()) {
                        val overwrite = channel.getPermissionOverwritesForRole(role.id)

                        val allowedPerms = overwrite?.allowed
                        val deniedPerms = overwrite?.denied

                        val hasNonDeniedPerms =
                            deniedPerms == null || SPEAKING_PERMISSIONS.any { !deniedPerms.contains(it) }

                        val canDenyNonAllowedPerms = allowedPerms == null || SPEAKING_PERMISSIONS.any {
                            !allowedPerms.contains(it) && deniedPerms?.contains(it) != true
                        }

                        if (hasNonDeniedPerms && canDenyNonAllowedPerms) {
                            channel.editRolePermission(role.id) {
                                SPEAKING_PERMISSIONS
                                    .filter { allowedPerms?.contains(it) != true }
                                    .forEach { denied += it }

                                SPEAKING_PERMISSIONS
                                    .filter { allowedPerms?.contains(it) == true }
                                    .forEach { allowed += it }

                                reason = "Mute role permissions update triggered by ${user.asUser().tag}"
                            }

                            channelsUpdated += 1
                        }
                    }

                    respond {
                        content = if (channelsUpdated > 0) {
                            "Updated permissions for $channelsUpdated channel/s."
                        } else {
                            "No channels to update."
                        }
                    }

                    val roleUpdated = if (role.permissions.values.isNotEmpty()) {
                        role.edit {
                            permissions = Permissions()

                            reason = "Mute role permissions update triggered by ${user.asUser().tag}"
                        }

                        respond { content = "Mute role permissions cleared." }

                        true
                    } else {
                        respond { content = "Mute role already has no extra permissions." }

                        false
                    }

                    if (channelsUpdated > 0 || roleUpdated) {
                        guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
                            title = "Mute role updated"
                            color = DISCORD_BLURPLE

                            description =
                                "Mute role (${role.mention} / `${role.id}`) permissions updated by " +
                                        "${user.mention}."

                            timestamp = Clock.System.now()

                            field {
                                name = "Channels Updated"
                                inline = true

                                value = "$channelsUpdated"
                            }

                            field {
                                name = "Role Updated"
                                inline = true

                                value = if (roleUpdated) "Yes" else "No"
                            }
                        }
                    }
                }
            }

            ephemeralSlashCommand {
                name = "lock-server"
                description = "Lock the server, preventing anyone but staff from talking"

                guild(guildId)

                check { hasPermission(Permission.Administrator) }

                action {
                    val roleId = when (guild!!.id) {
                        LADYSNAKE_GUILD -> LADYSNAKE_MODERATOR_ROLE
                        YOUTUBE_GUILD -> YOUTUBE_MODERATOR_ROLE

                        else -> throw DiscordRelayedException("Incorrect server ID: ${guild?.id?.value}")
                    }

                    val moderatorRole = guild!!.getRole(roleId)
                    val everyoneRole = guild!!.getRole(guild!!.id)

                    everyoneRole.edit {
                        permissions = everyoneRole.permissions
                            .minus(Permission.AddReactions)
                            .minus(Permission.CreatePrivateThreads)
                            .minus(Permission.CreatePublicThreads)
                            .minus(Permission.SendMessages)
                            .minus(Permission.SendMessagesInThreads)

                        reason = "Server locked down by ${user.asUser().tag}"
                    }

                    moderatorRole.edit {
                        permissions = moderatorRole.permissions
                            .plus(Permission.AddReactions)
                            .plus(Permission.CreatePrivateThreads)
                            .plus(Permission.CreatePublicThreads)
                            .plus(Permission.SendMessages)
                            .plus(Permission.SendMessagesInThreads)

                        reason = "Server locked down by ${user.asUser().tag}"
                    }

                    guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
                        title = "Server locked"
                        color = DISCORD_RED

                        description = "Server was locked by ${user.mention}."
                        timestamp = Clock.System.now()
                    }

                    respond {
                        content = "Server locked."
                    }
                }
            }

            ephemeralSlashCommand {
                name = "unlock-server"
                description = "Unlock the server, allowing users to talk again"

                guild(guildId)

                check { hasPermission(Permission.Administrator) }

                action {
                    val roleId = when (guild!!.id) {
                        LADYSNAKE_GUILD -> LADYSNAKE_MODERATOR_ROLE
                        YOUTUBE_GUILD -> YOUTUBE_MODERATOR_ROLE

                        else -> throw DiscordRelayedException("Incorrect server ID: ${guild?.id?.value}")
                    }

                    val moderatorRole = guild!!.getRole(roleId)
                    val everyoneRole = guild!!.getRole(guild!!.id)

                    everyoneRole.edit {
                        permissions = everyoneRole.permissions
                            .plus(Permission.AddReactions)
                            .plus(Permission.CreatePrivateThreads)
                            .plus(Permission.CreatePublicThreads)
                            .plus(Permission.SendMessages)
                            .plus(Permission.SendMessagesInThreads)

                        reason = "Server unlocked by ${user.asUser().tag}"
                    }

                    moderatorRole.edit {
                        permissions = moderatorRole.permissions
                            .minus(Permission.AddReactions)
                            .minus(Permission.CreatePrivateThreads)
                            .minus(Permission.CreatePublicThreads)
                            .minus(Permission.SendMessages)
                            .minus(Permission.SendMessagesInThreads)

                        reason = "Server unlocked by ${user.asUser().tag}"
                    }

                    guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
                        title = "Server unlocked"
                        color = DISCORD_GREEN

                        description = "Server was unlocked by ${user.mention}."
                        timestamp = Clock.System.now()
                    }

                    respond {
                        content = "Server unlocked."
                    }
                }
            }

            ephemeralSlashCommand(::LockArguments) {
                name = "lock"
                description = "Lock a channel, so only moderators can interact in it"

                guild(guildId)

                check { hasBaseModeratorRole() }

                action {
                    var channelObj = arguments.channel ?: channel.asChannel()

                    if (channelObj.type in THREAD_CHANNEL_TYPES) {
                        channelObj = channelObj.asChannelOf<ThreadChannel>().parent.asChannel()
                    }

                    if (channelObj.type !in TEXT_CHANNEL_TYPES) {  // Should never happen, but we handle it for safety
                        respond {
                            content = "This command can only be run in a guild text channel."
                        }
                    }

                    val staffRoleId = when (guild?.id) {
                        LADYSNAKE_GUILD -> LADYSNAKE_MODERATOR_ROLE
                        YOUTUBE_GUILD -> YOUTUBE_MODERATOR_ROLE

                        else -> null
                    }

                    val ch = channelObj.asChannelOf<TextChannel>()

                    if (staffRoleId != null) {
                        ch.editRolePermission(staffRoleId) {
                            SPEAKING_PERMISSIONS.forEach { allowed += it }

                            reason = "Channel locked by ${user.asUser().tag}"
                        }
                    }

                    ch.editRolePermission(guild!!.id) {
                        SPEAKING_PERMISSIONS.forEach { denied += it }

                        reason = "Channel locked by ${user.asUser().tag}"
                    }

                    ch.createMessage {
                        content = "Channel locked by a moderator."
                    }

                    guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
                        title = "Channel locked"
                        color = DISCORD_RED

                        description = "Channel ${ch.mention} was locked by ${user.mention}."
                        timestamp = Clock.System.now()
                    }

                    respond {
                        content = "Channel locked."
                    }
                }
            }

            ephemeralSlashCommand(::LockArguments) {
                name = "unlock"
                description = "Unlock a previously locked channel"

                guild(guildId)

                check { hasBaseModeratorRole() }

                action {
                    var channelObj = arguments.channel ?: channel.asChannel()

                    if (channelObj.type in THREAD_CHANNEL_TYPES) {
                        channelObj = channelObj.asChannelOf<ThreadChannel>().parent.asChannel()
                    }

                    if (channelObj.type !in TEXT_CHANNEL_TYPES) {  // Should never happen, but we handle it for safety
                        respond {
                            content = "This command can only be run in a guild text channel."
                        }
                    }

                    val ch = channelObj.asChannelOf<TextChannel>()

                    ch.getPermissionOverwritesForRole(guild!!.id)
                        ?.delete("Channel unlocked by ${user.asUser().tag}")

                    ch.createMessage {
                        content = "Channel unlocked by a moderator."
                    }

                    guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
                        title = "Channel unlocked"
                        color = DISCORD_GREEN

                        description = "Channel ${ch.mention} was unlocked by ${user.mention}."
                        timestamp = Clock.System.now()
                    }

                    respond {
                        content = "Channel unlocked."
                    }
                }
            }

            event<MessageCreateEvent> {
                check { inLadysnakeGuild() }
                check { isNotBot() }
                check { isNotInThread() }
                check { failIfNot(event.message.channelId in THREAD_ONLY_CHANNELS) }

                action {
                    val settings = event.getGuild()!!.getSettings() ?: return@action
                    if (event.message.channelId in settings.threadOnlyChannels) {
                        if (event.message.attachments.isEmpty()) {
                            event.message.delete("Found in thread-only channel without attachment")
                            event.member!!.dm {
                                content = "Your message in <#${event.message.channelId}> was deleted. " +
                                        "Please use the appropriate thread to talk about a post in this channel."
                            }
                        } else {
                            val channel = event.message.channel.asChannelOf<TextChannel>()

                            val messageContent = event.message.content

                            @Suppress("MagicNumber")
                            val threadName = if (messageContent.isBlank()) {
                                val attachments = event.message.attachments
                                if (attachments.size == 1) {
                                    attachments.first().filename
                                } else {
                                    attachments.size.toString() + " attachments"
                                }
                            } else if (messageContent.length > 25) {
                                messageContent.substring(0, 22) + "..."
                            } else {
                                messageContent
                            }

                            val thread = channel.startPublicThreadWithMessage(
                                event.message.id,
                                threadName,
                                event.getGuild()!!.getMaxArchiveDuration(),
                                "Automatic thread for thread-only channel"
                            )

                            logger.info { "Thread auto-created for ${event.message.author!!.tag}" }

                            val role = when (event.message.getGuild().id) {
                                LADYSNAKE_GUILD -> event.message.getGuild().getRole(LADYSNAKE_MODERATOR_ROLE)
                                YOUTUBE_GUILD -> event.message.getGuild().getRole(YOUTUBE_MODERATOR_ROLE)
                                else -> return@action
                            }

                            thread.addUser(event.message.author!!.id)

                            val message = thread.createMessage {
                                content = "Oh hey there, we just gotta do a bit of moderator-related setup " +
                                        "for this fun new creation..."
                            }

                            thread.withTyping {
                                delay(MESSAGE_EDIT_DELAY)
                            }

                            message.edit {
                                content = "Hey, ${role.mention}! Say cheese!"
                            }

                            thread.withTyping {
                                delay(MESSAGE_EDIT_DELAY)
                            }

                            message.edit {
                                content = "Welcome to your thread, ${event.message.author!!.mention}! " +
                                        "You can discuss your post with others here, and you can use the thread " +
                                        "commands, both `/thread` and the message commands, to manage your thread. "
                            }

                            message.pin("First message in the thread.")
                        }
                    }
                }
            }
        }
    }

    inner class PinMessageArguments : Arguments() {
        val message by message("message", "Message link or ID to pin/unpin")
    }

    inner class RenameArguments : Arguments() {
        val name by string("name", "Name to give the current thread")
    }

    inner class ArchiveArguments : Arguments() {
        val lock by defaultingBoolean(
            "lock",
            "Whether to lock the thread, if you're staff - defaults to false",
            false
        )
    }

    inner class LockArguments : Arguments() {
        val channel by optionalChannel(
            "channel",
            "Channel to lock/unlock, if not the current one"
        )
    }

    inner class MuteRoleArguments : Arguments() {
        val role by optionalRole("role", "Mute role ID, if the role isn't named Muted")
    }

    inner class SayArguments : Arguments() {
        val message by string("message", "Message to send")
        val target by optionalChannel("target", "Channel to use, if not this one") { _, value ->
            if (value != null && value !is GuildMessageChannel) {
                throw DiscordRelayedException("${value.mention} is not a guild text channel.")
            }
        }
    }
}
