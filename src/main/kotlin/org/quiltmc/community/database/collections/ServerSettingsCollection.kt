/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.database.collections

import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.common.entity.Snowflake
import org.koin.core.component.inject
import org.litote.kmongo.eq
import org.litote.kmongo.ne
import org.quiltmc.community.database.Collection
import org.quiltmc.community.database.Database
import org.quiltmc.community.database.entities.ServerSettings
import org.quiltmc.community.database.enums.LadysnakeServerType

class ServerSettingsCollection : KordExKoinComponent {
    private val database: Database by inject()
    private val col = database.mongo.getCollection<ServerSettings>(name)

    suspend fun get(id: Snowflake) =
        col.findOne(ServerSettings::_id eq id)

    fun getByLadysnakeServers() =
        col.find(ServerSettings::ladysnakeServerType ne null)

    fun getByServerType(type: LadysnakeServerType?) =
        col.find(ServerSettings::ladysnakeServerType eq type)

    suspend fun getLadysnake() =
        col.findOne(ServerSettings::ladysnakeServerType eq LadysnakeServerType.LADYSNAKE)

    suspend fun getYoutube() =
        col.findOne(ServerSettings::ladysnakeServerType eq LadysnakeServerType.YOUTUBE)

    suspend fun set(settings: ServerSettings) =
        col.save(settings)

    suspend fun delete(id: Snowflake) =
        col.deleteOne(ServerSettings::_id eq id)

    suspend fun delete(settings: ServerSettings) =
        delete(settings._id)

    companion object : Collection("server-settings")
}
