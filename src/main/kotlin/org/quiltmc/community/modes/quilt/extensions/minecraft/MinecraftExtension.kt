/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.minecraft

import com.kotlindiscord.kord.extensions.DISCORD_FUCHSIA
import com.kotlindiscord.kord.extensions.DISCORD_GREEN
import com.kotlindiscord.kord.extensions.checks.hasPermission
import com.kotlindiscord.kord.extensions.checks.hasRole
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalString
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.pagination.pages.Page
import com.kotlindiscord.kord.extensions.types.editingPaginator
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.scheduling.Scheduler
import com.kotlindiscord.kord.extensions.utils.scheduling.Task
import dev.kord.common.annotation.KordPreview
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.channel.NewsChannel
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import io.ktor.client.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import kotlinx.datetime.Clock
import mu.KotlinLogging
import org.apache.commons.text.StringEscapeUtils
import org.quiltmc.community.*

private const val PAGINATOR_TIMEOUT = 60_000L  // One minute
private const val CHUNK_SIZE = 10

private const val BASE_URL = "https://launchercontent.mojang.com"
private const val JSON_URL = "$BASE_URL/javaPatchNotes.json"

private const val CHECK_DELAY = 60L

private val LINK_REGEX = "<a href=\"(?<url>[^\"]+)\"[^>]*>(?<text>[^<]+)</a>".toRegex()

@Suppress("MagicNumber", "UnderscoresInNumericLiterals")
private val CHANNELS: List<Snowflake> = listOf(
    LADYSNAKE_GUILD,
//    YOUTUBE_GUILD,
)

class MinecraftExtension : Extension() {
    override val name: String = "minecraft"

    private val logger = KotlinLogging.logger { }

    private val client = HttpClient {
        install(JsonFeature)
    }

    private val scheduler = Scheduler()

    private var checkTask: Task? = null
    private var knownVersions: MutableSet<String> = mutableSetOf()
    private lateinit var currentEntries: PatchNoteEntries

    @OptIn(KordPreview::class)
    override suspend fun setup() {
        populateVersions()

        checkTask = scheduler.schedule(CHECK_DELAY, callback = ::checkTask)

        for (guildId in GUILDS) {
            ephemeralSlashCommand {
                name = "mc"
                description = "Commands related to Minecraft updates"

                guild(guildId)

                ephemeralSubCommand(::CheckArguments) {
                    name = "get"
                    description = "Retrieve the patch notes for a given Minecraft version, or the latest if not " +
                            "supplied."

                    action {
                        if (!::currentEntries.isInitialized) {
                            respond { content = "Still setting up - try again a bit later!" }
                            return@action
                        }

                        val patch = if (arguments.version == null) {
                            currentEntries.entries.first()
                        } else {
                            currentEntries.entries.firstOrNull { it.version.equals(arguments.version, true) }
                        }

                        if (patch == null) {
                            respond { content = "Unknown version supplied: `${arguments.version}`" }
                            return@action
                        }

                        respond {
                            embed {
                                patchNotes(patch)
                            }
                        }
                    }
                }

                ephemeralSubCommand {
                    name = "versions"
                    description = "Get a list of patch note versions."

                    action {
                        if (!::currentEntries.isInitialized) {
                            respond { content = "Still setting up - try again a bit later!" }

                            return@action
                        }

                        editingPaginator {
                            timeoutSeconds = PAGINATOR_TIMEOUT

                            knownVersions.chunked(CHUNK_SIZE).forEach { chunk ->
                                page(
                                    Page {
                                        title = "Patch note versions"
                                        color = DISCORD_FUCHSIA

                                        description = chunk.joinToString("\n") { "**»** `$it`" }

                                        footer {
                                            text = "${currentEntries.entries.size} versions"
                                        }
                                    }
                                )
                            }
                        }.send()
                    }
                }

                ephemeralSubCommand(::CheckArguments) {
                    name = "forget"
                    description = "Forget a version (the last one by default), allowing it to be relayed again."

                    when (guildId) {
                        LADYSNAKE_GUILD -> check { hasRole(LADYSNAKE_MODERATOR_ROLE) }
                        YOUTUBE_GUILD -> check { hasRole(YOUTUBE_MODERATOR_ROLE) }
                    }

                    check { hasPermission(Permission.Administrator) }

                    action {
                        if (!::currentEntries.isInitialized) {
                            respond { content = "Still setting up - try again a bit later!" }
                            return@action
                        }

                        val version = if (arguments.version == null) {
                            currentEntries.entries.first().version
                        } else {
                            currentEntries.entries.firstOrNull {
                                it.version.equals(arguments.version, true)
                            }?.version
                        }

                        if (version == null) {
                            respond { content = "Unknown version supplied: `${arguments.version}`" }
                            return@action
                        }

                        knownVersions.remove(version)

                        respond { content = "Version forgotten: `$version`" }
                    }
                }

                ephemeralSubCommand {
                    name = "run"
                    description = "Run the check task now, without waiting for it."

                    when (guildId) {
                        LADYSNAKE_GUILD -> check { hasRole(LADYSNAKE_MODERATOR_ROLE) }
                        YOUTUBE_GUILD -> check { hasRole(YOUTUBE_MODERATOR_ROLE) }
                    }

                    action {
                        respond { content = "Checking now..." }

                        checkTask?.callNow()
                    }
                }
            }
        }
    }

    suspend fun populateVersions() {
        currentEntries = client.get(JSON_URL)

        currentEntries.entries.forEach { knownVersions.add(it.version) }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun checkTask() {
        try {
            val now = Clock.System.now()
            currentEntries = client.get(JSON_URL + "?cbt=${now.epochSeconds}")

            currentEntries.entries.forEach {
                if (it.version !in knownVersions) {
                    relayUpdate(it)
                    knownVersions.add(it.version)
                }
            }
        } catch (t: Throwable) {
            logger.error(t) { "Check task run failed" }
        } finally {
            checkTask = scheduler.schedule(CHECK_DELAY, callback = ::checkTask)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun relayUpdate(patchNote: PatchNote) =
        CHANNELS
            .map {
                try {
                    kord.getChannelOf<TopGuildMessageChannel>(it)
                } catch (t: Throwable) {
                    logger.warn(t) { "Unable to get channel of ID: ${it.value}" }

                    null
                }
            }
            .filterNotNull()
            .forEach { it.relay(patchNote) }

    fun String.formatHTML(): String {
        var result = this

        result = result.replace("[\n]*</p>\n+<p>[\n]*".toRegex(), "\n\n")
        result = result.replace("[\n]*<[/]*p>[\n]*".toRegex(), "\n")

        result = result.replace("<strong>", "**")
        result = result.replace("</strong>", "**")

        result = result.replace("<code>", "`")
        result = result.replace("</code>", "`")

        result = result.replace("[\n]*<h\\d+>[\n]*".toRegex(), "\n\n__**")
        result = result.replace("[\n]*</h\\d+>[\n]*".toRegex(), "**__\n")

        result = result.replace("[\n]*<[ou]l>[\n]*".toRegex(), "\n\n")
        result = result.replace("[\n]*</[ou]l>[\n]*".toRegex(), "\n\n")

        result = result.replace("[\n]*</li>\n+<li>[\n]*".toRegex(), "\n**»** ")
        result = result.replace("([\n]{2,})?<li>[\n]*".toRegex(), "\n**»** ")
        result = result.replace("[\n]*</li>[\n]*".toRegex(), "\n\n")

        val links = LINK_REGEX.findAll(result)

        links.forEach {
            result = result.replace(
                it.value,
                "[${it.groups["text"]?.value}](${it.groups["url"]?.value})"
            )
        }

        return StringEscapeUtils.unescapeHtml4(result.trim('\n'))
    }

    fun String.truncateMarkdown(maxLength: Int = 1000): Pair<String, Int> {
        var result = this

        if (length > maxLength) {
            val truncated = result.substring(0, maxLength).substringBeforeLast("\n")
            val remaining = result.substringAfter(truncated).count { it == '\n' }

            result = truncated

            return result to remaining
        }

        return result to 0
    }

    private fun EmbedBuilder.patchNotes(patchNote: PatchNote, maxLength: Int = 1000) {
        val (truncated, remaining) = patchNote.body.formatHTML().truncateMarkdown(maxLength)

        title = patchNote.title
        color = DISCORD_GREEN

        description = "[Full patch notes](https://quiltmc.org/mc-patchnotes/#${patchNote.version})\n\n"
        description += truncated

        if (remaining > 0) {
            description += "\n\n[... $remaining more lines]"
        }

        thumbnail {
            url = "$BASE_URL${patchNote.image.url}"
        }

        footer {
            text = "URL: https://quiltmc.org/mc-patchnotes/#${patchNote.version}"
        }
    }

    private suspend fun TopGuildMessageChannel.relay(patchNote: PatchNote, maxLength: Int = 1000) {
        val message = createMessage { embed { patchNotes(patchNote, maxLength) } }

        val title = if (patchNote.title.startsWith("minecraft ", true)) {
            patchNote.title.split(" ", limit = 2).last()
        } else {
            patchNote.title
        }

        if (guildId == LADYSNAKE_GUILD) {
            when (this) {
                is TextChannel -> startPublicThreadWithMessage(
                    message.id,
                    title,
                    guild.asGuild().getMaxArchiveDuration()
                )

                is NewsChannel -> startPublicThreadWithMessage(
                    message.id,
                    title,
                    guild.asGuild().getMaxArchiveDuration()
                )
            }
        }
    }

    @OptIn(KordPreview::class)
    class CheckArguments : Arguments() {
        val version by optionalString {
            name = "version"
            description = "Specific version to get patch notes for"
        }
    }
}
