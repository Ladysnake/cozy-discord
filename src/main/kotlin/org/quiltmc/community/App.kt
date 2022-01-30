/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(PrivilegedIntent::class)

/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package org.quiltmc.community

import com.kotlindiscord.kord.extensions.ExtensibleBot
import com.kotlindiscord.kord.extensions.checks.hasPermission
import com.kotlindiscord.kord.extensions.modules.extra.mappings.extMappings
import com.kotlindiscord.kord.extensions.modules.extra.phishing.DetectionAction
import com.kotlindiscord.kord.extensions.modules.extra.phishing.extPhishing
import com.kotlindiscord.kord.extensions.utils.envOrNull
import com.kotlindiscord.kord.extensions.utils.getKoin
import dev.kord.common.entity.Permission
import dev.kord.gateway.Intents
import dev.kord.gateway.PrivilegedIntent
import org.quiltmc.community.cozy.modules.cleanup.userCleanup
import org.quiltmc.community.database.collections.ServerSettingsCollection
import org.quiltmc.community.modes.quilt.extensions.*
import org.quiltmc.community.modes.quilt.extensions.filtering.FilterExtension
import org.quiltmc.community.modes.quilt.extensions.github.GithubExtension
import org.quiltmc.community.modes.quilt.extensions.messagelog.MessageLogExtension
import org.quiltmc.community.modes.quilt.extensions.minecraft.MinecraftExtension
import org.quiltmc.community.modes.quilt.extensions.moderation.ModerationExtension
import org.quiltmc.community.modes.quilt.extensions.settings.SettingsExtension
import org.quiltmc.community.modes.quilt.extensions.suggestions.SuggestionsExtension
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

val MODE = envOrNull("MODE")?.lowercase() ?: "ladysnake"

suspend fun setupLadysnake() = ExtensibleBot(DISCORD_TOKEN) {
    common()
    database(true)
    settings()

    chatCommands {
        enabled = true
    }

    intents {
        +Intents.all
    }

    members {
        all()

        fillPresences = true
    }

    extensions {
        add(::FilterExtension)
        add(::MessageLogExtension)
        add(::MinecraftExtension)
        add(::PKExtension)
        add(::SettingsExtension)
        add(::SuggestionsExtension)
        add(::SyncExtension)
//        add(::UserCleanupExtension)
        add(::UtilityExtension)
        add(::ModerationExtension)
        add(::UserFunExtension)
        add(::PersistentCacheExtension)

        if (GITHUB_TOKEN != null) {
            add(::GithubExtension)
        }

        extMappings { }

        extPhishing {
            appName = "Ladysnake's Modification of Quilt's Cozy Bot"
            detectionAction = DetectionAction.Kick
            logChannelName = "hissie-logs"
            requiredCommandPermission = null

            check { inLadysnakeGuild() }
            check { notHasBaseModeratorRole() }
        }

        userCleanup {
            maxPendingDuration = 3.days
            taskDelay = 1.hours
            loggingChannelName = "cozy-logs"

            runAutomatically = false

            guildPredicate {
                val servers = getKoin().get<ServerSettingsCollection>()
                val serverEntry = servers.get(it.id)

                serverEntry?.ladysnakeServerType != null
            }

            commandCheck { hasPermission(Permission.Administrator) }
        }

        sentry {
            distribution = "ladysnake"
        }
    }
}

@Suppress("UseIfInsteadOfWhen") // currently only one mode but that could change
suspend fun main() {
    val bot = when (MODE) {
        "ladysnake" -> setupLadysnake()

        else -> error("Invalid mode: $MODE")
    }

    bot.start()
}
