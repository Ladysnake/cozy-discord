/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.database.migrations

import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.exists
import org.litote.kmongo.setValue
import org.quiltmc.community.LADYSNAKE_GUILD
import org.quiltmc.community.LADYSNAKE_SUGGESTION_CHANNEL
import org.quiltmc.community.database.collections.InvalidMentionsCollection
import org.quiltmc.community.database.collections.SuggestionsCollection
import org.quiltmc.community.database.collections.UserRestrictionsCollection
import org.quiltmc.community.database.entities.Suggestion

suspend fun v10(db: CoroutineDatabase) {
    db.createCollection(InvalidMentionsCollection.name)
    db.createCollection(UserRestrictionsCollection.name)

    with(db.getCollection<Suggestion>(SuggestionsCollection.name)) {
        updateMany(
            Suggestion::guildId exists false,
            setValue(Suggestion::guildId, LADYSNAKE_GUILD)
        )

        updateMany(
            Suggestion::channelId exists false,
            setValue(Suggestion::channelId, LADYSNAKE_SUGGESTION_CHANNEL)
        )
    }
}
