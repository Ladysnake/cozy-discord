@file:Suppress("MagicNumber", "UnderscoresInNumericLiterals")

package org.quiltmc.community

import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.DISCORD_GREEN
import com.kotlindiscord.kord.extensions.DISCORD_RED
import com.kotlindiscord.kord.extensions.utils.env
import com.kotlindiscord.kord.extensions.utils.envOrNull
import dev.kord.common.entity.Snowflake

internal val DISCORD_TOKEN = env("TOKEN")
internal val GITHUB_TOKEN = envOrNull("GITHUB_TOKEN")

internal val MAIN_GUILD = Snowflake(
    envOrNull("MAIN_GUILD_ID")?.toULong()
        ?: envOrNull("LADYSNAKE_GUILD_ID")?.toULong()
        ?: 292744693803122688U
)

internal val MESSAGE_LOG_CATEGORIES = envOrNull("MESSAGE_LOG_CATEGORIES")?.split(',')
    ?.map { Snowflake(it.trim()) } ?: listOf()

internal val COLOUR_BLURPLE = DISCORD_BLURPLE
internal val COLOUR_NEGATIVE = DISCORD_RED
internal val COLOUR_POSITIVE = DISCORD_GREEN

internal val LADYSNAKE_MODERATOR_ROLE = envOrNull("LADYSNAKE_MODERATOR_ROLE")?.let { Snowflake(it) }
    ?: Snowflake(807373579946688533)

internal val YOUTUBE_MODERATOR_ROLE = envOrNull("YOUTUBE_MODERATOR_ROLE")?.let { Snowflake(it) }
    ?: Snowflake(863767485609541632)

internal val MODERATOR_ROLES: List<Snowflake> =
    (envOrNull("MODERATOR_ROLES") ?: envOrNull("COMMUNITY_MANAGEMENT_ROLES")) // For now, back compat
        ?.split(',')
        ?.map { Snowflake(it.trim()) }
        ?: listOf(LADYSNAKE_MODERATOR_ROLE, YOUTUBE_MODERATOR_ROLE)

internal val LADYSNAKE_GUILD = Snowflake(
    envOrNull("LADYSNAKE_GUILD_ID")?.toLong() ?: 292744693803122688
)

internal val YOUTUBE_GUILD = Snowflake(
    envOrNull("YOUTUBE_GUILD_ID")?.toLong() ?: 924373275688198204
)

internal val GUILDS = envOrNull("GUILDS")?.split(',')?.map { Snowflake(it.trim()) }
    ?: listOf(LADYSNAKE_GUILD, YOUTUBE_GUILD)

internal val SUGGESTION_CHANNEL = Snowflake(
    envOrNull("SUGGESTION_CHANNEL_ID")?.toLong() ?: 477596847129624591
)

internal val GITHUB_LOG_CHANNEL = Snowflake(
    envOrNull("GITHUB_LOG_CHANNEL_ID")?.toLong() ?: 558629602206023691
)

internal val MAX_MESSAGES_PER_MINUTE = envOrNull("MAX_MESSAGES_PER_MINUTE")?.toInt() ?: 20
internal val MAX_MESSAGES_PER_SECOND = envOrNull("MAX_MESSAGES_PER_SECOND")?.toInt() ?: 3
internal val MAX_MENTIONS_PER_MESSAGE = envOrNull("MAX_MENTIONS_PER_MESSAGE")?.toInt() ?: 3
