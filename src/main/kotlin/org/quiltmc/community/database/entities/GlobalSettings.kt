@file:UseSerializers(UUIDSerializer::class)

@file:Suppress("DataClassShouldBeImmutable", "DataClassContainsFunctions")  // Well, yes, but actually no.

package org.quiltmc.community.database.entities

import com.github.jershell.kbson.UUIDSerializer
import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.utils.getKoin
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.rest.builder.message.EmbedBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.quiltmc.community.GITHUB_LOG_CHANNEL
import org.quiltmc.community.LADYSNAKE_GUILD
import org.quiltmc.community.SUGGESTION_CHANNEL
import org.quiltmc.community.database.Entity
import org.quiltmc.community.database.collections.GlobalSettingsCollection
import org.quiltmc.community.getGuildIgnoring403
import java.util.*

@Serializable
@Suppress("ConstructorParameterNaming")  // MongoDB calls it that...
data class GlobalSettings(
    override val _id: UUID = UUID.randomUUID(),

    var appealsInvite: String? = null,
    var githubToken: String? = null,

    val ladysnakeGuilds: MutableSet<Snowflake> = mutableSetOf(
        LADYSNAKE_GUILD,
//        YOUTUBE_GUILD,
    ),

    var suggestionChannel: Snowflake? = SUGGESTION_CHANNEL,
    var githubLogChannel: Snowflake? = GITHUB_LOG_CHANNEL,
) : Entity<UUID> {
    suspend fun save() {
        val collection = getKoin().get<GlobalSettingsCollection>()

        collection.set(this)
    }

    suspend fun apply(embedBuilder: EmbedBuilder) {
        val kord = getKoin().get<Kord>()
        val builder = StringBuilder()

        builder.append("**Appeals Invite:** ")

        if (appealsInvite != null) {
            builder.append("https://discord.gg/$appealsInvite")
        } else {
            builder.append(":x: Not configured")
        }

        builder.append("\n")
        builder.append("**GitHub Token:** ")

        if (githubToken != null) {
            builder.append("Configured")
        } else {
            builder.append(":x: Not configured")
        }

        builder.append("\n\n")
        builder.append("**Suggestions Channel:** ")

        if (suggestionChannel != null) {
            builder.append("<#${suggestionChannel!!.value}>")
        } else {
            builder.append(":x: Not configured")
        }

        builder.append("\n")
        builder.append("**/github Log Channel:** ")

        if (githubLogChannel != null) {
            builder.append("<#${githubLogChannel!!.value}>")
        } else {
            builder.append(":x: Not configured")
        }

        builder.append("\n\n")
        builder.append("__**Ladysnake Servers**__\n")

        if (ladysnakeGuilds.isNotEmpty()) {
            ladysnakeGuilds.forEach {
                val guild = kord.getGuildIgnoring403(it)

                if (guild == null) {
                    builder.append("**»** `${it.value}`\n")
                } else {
                    builder.append("**»** ${guild.name} (`${it.value}`)\n")
                }
            }
        } else {
            builder.append(":x: No servers configured")
        }

        with(embedBuilder) {
            title = "Global Settings"
            color = DISCORD_BLURPLE

            description = builder.toString()
        }
    }
}
