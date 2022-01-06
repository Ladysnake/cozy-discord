package org.quiltmc.community.database.collections

import dev.kord.common.entity.Snowflake
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.litote.kmongo.eq
import org.quiltmc.community.database.Collection
import org.quiltmc.community.database.Database
import org.quiltmc.community.database.entities.Lottery

class LotteryCollection : KoinComponent {
    val db: Database by inject()
    val collection = db.mongo.getCollection<Lottery>(name)

    suspend fun get(id: Snowflake) =
        collection.findOne(Lottery::_id eq id)

    suspend fun save(lottery: Lottery) =
        collection.save(lottery)

    suspend fun delete(id: Snowflake) =
        collection.deleteOne(Lottery::_id eq id)

    companion object : Collection("lottery")
}
