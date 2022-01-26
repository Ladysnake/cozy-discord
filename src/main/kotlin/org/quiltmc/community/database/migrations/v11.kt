/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.database.migrations

import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.exists
import org.litote.kmongo.setValue
import org.quiltmc.community.LADYSNAKE_SUGGESTION_CHANNEL
import org.quiltmc.community.database.collections.GlobalSettingsCollection
import org.quiltmc.community.database.collections.LotteryCollection
import org.quiltmc.community.database.collections.ServerSettingsCollection
import org.quiltmc.community.database.entities.GlobalSettings
import org.quiltmc.community.database.entities.ServerSettings

suspend fun v11(db: CoroutineDatabase) {
    db.createCollection(LotteryCollection.name)

    with(db.getCollection<GlobalSettings>(GlobalSettingsCollection.name)) {
        updateMany(
            GlobalSettings::suggestionChannels exists false,
            setValue(GlobalSettings::suggestionChannels, mutableSetOf(LADYSNAKE_SUGGESTION_CHANNEL))
        )
    }

    with(db.getCollection<ServerSettings>(ServerSettingsCollection.name)) {
        updateMany(
            ServerSettings::threadOnlyChannels exists false,
            setValue(ServerSettings::threadOnlyChannels, mutableSetOf())
        )
    }
}
