package org.quiltmc.community.database.collections

import dev.kord.common.entity.Snowflake
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.litote.kmongo.eq
import org.quiltmc.community.database.Collection
import org.quiltmc.community.database.Database
import org.quiltmc.community.database.entities.InvalidMention

class InvalidMentionsCollection : KoinComponent {
    private val db: Database by inject()
    private val collection = db.mongo.getCollection<InvalidMention>(name)

    suspend fun get(id: Snowflake) =
        collection.findOne(InvalidMention::_id eq id)

    suspend fun set(mention: InvalidMention) =
        collection.save(mention)

    suspend fun delete(id: Snowflake) =
        collection.deleteOne(InvalidMention::_id eq id)

    companion object : Collection("invalid-mentions")
}
