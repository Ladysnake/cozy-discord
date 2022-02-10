/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:Suppress("NOTHING_TO_INLINE")
@file:OptIn(ExperimentalTime::class)

package org.quiltmc.community

import com.kotlindiscord.kord.extensions.builders.ExtensibleBotBuilder
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.SlashCommandContext
import com.kotlindiscord.kord.extensions.utils.env
import com.kotlindiscord.kord.extensions.utils.envOrNull
import com.kotlindiscord.kord.extensions.utils.getKoin
import com.kotlindiscord.kord.extensions.utils.loadModule
import dev.kord.common.Color
import dev.kord.common.entity.ArchiveDuration
import dev.kord.common.entity.DiscordEmbed
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.Optional
import dev.kord.common.entity.optional.value
import dev.kord.core.Kord
import dev.kord.core.behavior.UserBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.asChannelOfOrNull
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.getChannelOf
import dev.kord.core.entity.*
import dev.kord.core.entity.channel.GuildChannel
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.request.RestRequestException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toInstant
import org.koin.dsl.bind
import org.quiltmc.community.database.Database
import org.quiltmc.community.database.collections.*
import org.quiltmc.community.database.entities.ServerSettings
import org.quiltmc.community.database.getSettings
import org.quiltmc.community.modes.quilt.extensions.settings.SettingsExtension
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@Suppress("MagicNumber")  // It's the status code...
suspend fun Kord.getGuildIgnoring403(id: Snowflake) =
    try {
        getGuild(id)
    } catch (e: RestRequestException) {
        if (e.status.code != 403) {
            throw(e)
        }

        null
    }

/**
 * Time out a user. This is an extension function at this time
 * because it is not currently implemented in Kord or elsewhere in Kordex
 */
suspend fun Member.timeout(length: Duration) {
    edit {
        communicationDisabledUntil = Clock.System.now() + length
    }
}

/**
 * Time out a user. This is an extension function at this time
 * because it is not currently implemented in Kord or elsewhere in Kordex
 */
suspend fun Member.timeoutUntil(time: Instant) {
    edit {
        communicationDisabledUntil = time
    }
}

suspend fun Guild.getModLogChannel() =
    channels.firstOrNull { it.name == "moderation-log" }
        ?.asChannelOfOrNull<GuildMessageChannel>()

fun String.chunkByWhitespace(length: Int): List<String> {
    if (length <= 0) {
        error("Length must be greater than 0")
    }

    if (contains("\n")) {
        error("String must be a single line")
    }

    val words = split(" ")
    var currentLine = ""
    val lines: MutableList<String> = mutableListOf()

    for (word in words) {
        if (word.length >= length) {
            val parts = word.chunked(length)

            if (currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = ""
            }

            parts.forEach {
                if (it.length == length) {
                    lines.add(it)
                } else if (it.isNotEmpty()) {
                    currentLine = it
                }
            }
        } else {
            val newLength = currentLine.length + word.length + if (currentLine.isEmpty()) 0 else 1

            if (newLength > length) {
                lines.add(currentLine)
                currentLine = word
            } else {
                currentLine += if (currentLine.isEmpty()) word else " $word"
            }
        }
    }

    if (currentLine.isNotEmpty()) {
        lines.add(currentLine)
    }

    return lines
}

suspend fun ExtensibleBotBuilder.database(migrate: Boolean = false) {
    val url = env("DB_URL")
    val db = Database(url)

    hooks {
        beforeKoinSetup {
            loadModule {
                single { db } bind Database::class
            }

            loadModule {
                single { FilterCollection() } bind FilterCollection::class
                single { FilterEventCollection() } bind FilterEventCollection::class
                single { GlobalSettingsCollection() } bind GlobalSettingsCollection::class
                single { MetaCollection() } bind MetaCollection::class
                single { OwnedThreadCollection() } bind OwnedThreadCollection::class
                single { ServerSettingsCollection() } bind ServerSettingsCollection::class
                single { SuggestionsCollection() } bind SuggestionsCollection::class
                single { TeamCollection() } bind TeamCollection::class
                single { UserFlagsCollection() } bind UserFlagsCollection::class
                single { InvalidMentionsCollection() } bind InvalidMentionsCollection::class
                single { UserRestrictionsCollection() } bind UserRestrictionsCollection::class
                single { LotteryCollection() } bind LotteryCollection::class
            }

            if (migrate) {
                runBlocking {
                    db.migrate()
                }
            }
        }
    }
}

suspend fun ExtensibleBotBuilder.common() {
    chatCommands {
        defaultPrefix = "?"

        prefix { default ->
            getGuild()?.getSettings()?.commandPrefix ?: default
        }

        check {
            if (event.message.author == null) {
                fail()
            }
        }
    }

    extensions {
        sentry {
            val sentryDsn = envOrNull("SENTRY_DSN")

            if (sentryDsn != null) {
                enable = true

                dsn = sentryDsn
            }
        }
    }
}

suspend fun ExtensibleBotBuilder.settings() {
    extensions {
        add(::SettingsExtension)
    }
}

fun Guild.getMaxArchiveDuration(): ArchiveDuration {
    val features = features.filter {
        it.value == "THREE_DAY_THREAD_ARCHIVE" ||
                it.value == "SEVEN_DAY_THREAD_ARCHIVE"
    }.map { it.value }

    return when {
        features.contains("SEVEN_DAY_THREAD_ARCHIVE") -> ArchiveDuration.Week
        features.contains("THREE_DAY_THREAD_ARCHIVE") -> ArchiveDuration.ThreeDays

        else -> ArchiveDuration.Day
    }
}

suspend fun GuildMessageChannel.getArchiveDuration(settings: ServerSettings?): ArchiveDuration {
    return data.defaultAutoArchiveDuration.value
        ?: settings?.defaultThreadLength
        ?: getGuild().getMaxArchiveDuration()
}

// Logging-related extensions

suspend fun <C : SlashCommandContext<C, A>, A : Arguments>
        SlashCommandContext<C, A>.getGithubLogChannel(): GuildMessageChannel? {
    val channelId = getKoin().get<GlobalSettingsCollection>().get()?.githubLogChannel ?: return null

    return event.kord.getChannelOf<GuildMessageChannel>(channelId)
}

suspend fun Kord?.getGithubLogChannel(): GuildMessageChannel? {
    val channelId = getKoin().get<GlobalSettingsCollection>().get()?.githubLogChannel ?: return null

    return this?.getChannelOf(channelId)
}

suspend fun Guild.getCozyLogChannel(): GuildMessageChannel? {
    val channelId = getKoin().get<ServerSettingsCollection>().get(id)?.cozyLogChannel ?: return null

    return getChannelOf(channelId)
}

suspend fun Guild.getFilterLogChannel(): GuildMessageChannel? {
    val channelId = getKoin().get<ServerSettingsCollection>().get(id)?.filterLogChannel ?: return null

    return getChannelOf(channelId)
}

suspend fun EmbedBuilder.userField(user: UserBehavior, role: String, inline: Boolean = false) {
    field {
        name = role
        value = "${user.mention} (`${user.id}` / `${user.asUser().tag}`)"

        this.inline = inline
    }
}

fun EmbedBuilder.channelField(channel: MessageChannelBehavior, title: String, inline: Boolean = false) {
    field {
        this.name = title
        this.value = "${channel.mention} (`${channel.id}`)"

        this.inline = inline
    }
}

suspend inline fun UserBehavior.softMention() = "@${asUser().tag}"

suspend inline fun Snowflake.asGuild(): Guild? = getKoin().get<Kord>().getGuild(this)

suspend inline fun Snowflake.asChannel(guild: Snowflake): GuildChannel? = guild.asGuild()?.getChannelOrNull(this)

suspend inline fun <reified T : GuildChannel> Snowflake.asChannelOf(guild: Snowflake): T? =
    guild.asGuild()?.getChannelOf(this)

suspend inline fun Snowflake.asRole(guild: Snowflake): Role? = guild.asGuild()?.getRoleOrNull(this)

suspend inline fun Snowflake.asMember(guild: Snowflake): Member? = guild.asGuild()?.getMemberOrNull(this)

suspend inline fun Snowflake.asUser(): User? = getKoin().get<Kord>().getUser(this)

inline fun EmbedBuilder.copyFrom(embed: Embed) {
    title = embed.title
    description = embed.description
    color = embed.color
    timestamp = embed.timestamp
    url = embed.url
    image = embed.image?.url

    if (embed.footer != null) {
        footer {
            text = embed.footer!!.text
            icon = embed.footer!!.iconUrl
        }
    }

    if (embed.thumbnail != null) {
        thumbnail {
            if (embed.thumbnail!!.url != null) {
                url = embed.thumbnail!!.url!!
            }
        }
    }

    if (embed.author != null) {
        author {
            name = embed.author!!.name
            url = embed.author!!.url
            icon = embed.author!!.iconUrl
        }
    }

    embed.fields.forEach {
        field {
            name = it.name
            value = it.value
            inline = it.inline
        }
    }
}

inline fun EmbedBuilder.copyFrom(embed: DiscordEmbed) {
    // DiscordEmbed has the same structure as Embed, but it's a direct json implementation rather than a friendly object
    title = embed.title.value
    description = embed.description.value
    color = embed.color.value?.let { Color(it) }
    timestamp = embed.timestamp.value?.toInstant()
    url = embed.url.value
    image = embed.image.value?.url?.value

    if (embed.footer is Optional.Value) {
        val footer = embed.footer.value!!
        footer {
            text = footer.text
            icon = footer.iconUrl.value
        }
    }

    if (embed.thumbnail is Optional.Value) {
        val thumbnail = embed.thumbnail.value!!
        thumbnail {
            if (thumbnail.url.value != null) {
                url = thumbnail.url.value!!
            }
        }
    }

    if (embed.author is Optional.Value) {
        val author = embed.author.value!!
        author {
            name = author.name.value
            url = author.url.value
            icon = author.iconUrl.value
        }
    }

    embed.fields.value?.forEach {
        field {
            name = it.name
            value = it.value
            inline = it.inline.value
        }
    }
}

fun Color.awt() = java.awt.Color(red, green, blue)

fun String.italic() = "*$this*"
fun String.bold() = "**$this**"
fun String.underline() = "__${this}__"
fun String.strikethrough() = "~~$this~~"
fun String.code() = "`$this`"
fun String.spoiler() = "||$this||"
fun String.quote() = ">>> $this"

fun String.codeBlock(language: String = "") = """
    |```$language
    |${this.replace("\n", "\n    |")}
    |```
""".trimMargin()

fun Snowflake.stringCode() = toString().code()
