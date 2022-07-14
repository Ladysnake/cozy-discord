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
import org.quiltmc.community.database.Collection
import org.quiltmc.community.database.Database
import org.quiltmc.community.database.entities.UserRestrictions

class UserRestrictionsCollection : KordExKoinComponent {
    val database: Database by inject()
    val collection = database.mongo.getCollection<UserRestrictions>(name)

    suspend fun get(id: Snowflake) =
        collection.findOne(UserRestrictions::_id eq id)

    suspend fun getAll() =
        collection.find().toList()

    suspend fun set(restrictions: UserRestrictions) =
        collection.save(restrictions)

    suspend fun remove(id: Snowflake) =
        collection.deleteOne(UserRestrictions::_id eq id)

    companion object : Collection("user-restrictions")
}
