/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.suggestions

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.serialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.intellij.lang.annotations.Language

@Serializable
data class AutoRemoval(
    val id: String,
    @Serializable(with = RegexSerializer::class)
    val regex: Regex,
    val status: SuggestionStatus,
    val reason: String
) {
    constructor(
        id: String,
        @Language("RegExp") regex: String,
        status: SuggestionStatus,
        reason: String
    ) : this(id, Regex(regex, RegexOption.IGNORE_CASE), status, reason)

    override fun toString() = "`$id`: `$regex` -> ${status.readableName} / \"$reason\""
}

val defaultAutoRemovals = listOf(
    AutoRemoval(
        "forge",
        "forge port|port( .+)? to forge|make( it)? forge",
        SuggestionStatus.Denied,
        "Ladysnake mods will not get first-party support for Forge. Third-party contributors may make " +
                "their own ports for Forge if they wish."
    ),
    AutoRemoval(
        "new-mod",
        "new mod",
        SuggestionStatus.Denied,
        "This is for requesting suggestions for existing mods, not ideas for new mods."
    )
)

object RegexSerializer : KSerializer<Regex> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Regex") {
            element("pattern", serialDescriptor<String>())
            element("flags", IntArraySerializer().descriptor)
        }

    override fun deserialize(decoder: Decoder): Regex {
        val pattern = decoder.decodeString()
        val flags = IntArraySerializer().deserialize(decoder)
        return Regex(pattern, flags.map { RegexOption.values()[it] }.toSet())
    }

    override fun serialize(encoder: Encoder, value: Regex) {
        encoder.encodeString(value.pattern)
        IntArraySerializer().serialize(encoder, value.options.map { it.ordinal }.toIntArray())
    }
}
