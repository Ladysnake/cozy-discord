@file:Suppress("DataClassShouldBeImmutable", "DataClassContainsFunctions")  // Well, yes, but actually no.

package org.quiltmc.community.database.entities

import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.utils.getKoin
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.entity.channel.Category
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.rest.builder.message.EmbedBuilder
import kotlinx.serialization.Serializable
import org.quiltmc.community.database.Entity
import org.quiltmc.community.database.collections.ServerSettingsCollection
import org.quiltmc.community.database.enums.LadysnakeServerType
import org.quiltmc.community.getGuildIgnoring403

@Serializable
@Suppress("ConstructorParameterNaming")  // MongoDB calls it that...
data class ServerSettings(
    override val _id: Snowflake,

    var commandPrefix: String? = "?",
    val moderatorRoles: MutableSet<Snowflake> = mutableSetOf(),

    var cozyLogChannel: Snowflake? = null,
    var messageLogCategory: Snowflake? = null,

    var ladysnakeServerType: LadysnakeServerType? = null,
    var leaveServer: Boolean = false,
    val threadOnlyChannels: MutableSet<Snowflake> = mutableSetOf(),
) : Entity<Snowflake> {
    suspend fun save() {
        val collection = getKoin().get<ServerSettingsCollection>()

        collection.set(this)
    }

    suspend fun getConfiguredLogChannel(): TopGuildMessageChannel? {
        cozyLogChannel ?: return null

        val kord = getKoin().get<Kord>()
        val guild = kord.getGuildIgnoring403(_id)

        return guild?.getChannelOfOrNull(cozyLogChannel!!)
    }

    suspend fun getConfiguredMessageLogCategory(): Category? {
        messageLogCategory ?: return null

        val kord = getKoin().get<Kord>()
        val guild = kord.getGuildIgnoring403(_id)

        return guild?.getChannelOfOrNull(messageLogCategory!!)
    }

    suspend fun apply(embedBuilder: EmbedBuilder, showQuiltSettings: Boolean) {
        val kord = getKoin().get<Kord>()
        val builder = StringBuilder()
        val guild = kord.getGuildIgnoring403(_id)

        if (guild != null) {
            builder.append("**Guild ID:** `${_id.value}`\n")
        }

        builder.append("**Command Prefix:** `$commandPrefix`\n\n")
        builder.append("**Cozy Logs:** ")

        if (cozyLogChannel != null) {
            builder.append("<#${cozyLogChannel!!.value}>")
        } else {
            builder.append(":x: Not configured")
        }

        builder.append("\n")
        builder.append("**Message Logs:** ")

        if (messageLogCategory != null) {
            builder.append("<#${messageLogCategory!!.value}>")
        } else {
            builder.append(":x: Not configured")
        }

        builder.append("\n\n")

        if (showQuiltSettings) {
            builder.append("**Ladysnake Server Type:** ")

            if (ladysnakeServerType != null) {
                builder.append(ladysnakeServerType!!.readableName)
            } else {
                builder.append(":x: Not configured")
            }

            builder.append("\n")
        }

        builder.append("**Leave Server Automatically:** ")

        if (leaveServer) {
            builder.append("Yes")
        } else {
            builder.append("No")
        }

        builder.append("\n\n")
        builder.append("**__Moderator Roles__**\n")

        if (moderatorRoles.isNotEmpty()) {
            moderatorRoles.forEach {
                val role = guild?.getRoleOrNull(it)

                if (role != null) {
                    builder.append("**»** **${role.name}** (`${it.value}`)\n")
                } else {
                    builder.append("**»** `${it.value}`\n")
                }
            }
        } else {
            builder.append(":x: No roles configured")
        }

        builder.append("\n\n")
        builder.append("**__Thread Only Channels__**\n")

        if (threadOnlyChannels.isNotEmpty()) {
            threadOnlyChannels.forEach {
                val channel = guild?.getChannelOfOrNull<GuildMessageChannel>(it)

                if (channel != null) {
                    builder.append("**»** **${channel.name}** (`${it.value}`)\n")
                } else {
                    builder.append("**»** `${it.value}`\n")
                }
            }
        } else {
            builder.append(":x: No channels configured")
        }

        with(embedBuilder) {
            color = DISCORD_BLURPLE
            description = builder.toString()
            title = "Settings"

            title += if (guild != null) {
                ": ${guild.name}"
            } else {
                " (${_id.value})"
            }
        }
    }
}
