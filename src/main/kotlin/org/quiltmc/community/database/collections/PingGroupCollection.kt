package org.quiltmc.community.database.collections

import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.common.entity.Snowflake
import org.koin.core.component.inject
import org.litote.kmongo.and
import org.litote.kmongo.contains
import org.litote.kmongo.eq
import org.quiltmc.community.database.Collection
import org.quiltmc.community.database.Database
import org.quiltmc.community.database.entities.PingGroup

class PingGroupCollection : KordExKoinComponent {
	private val database: Database by inject()
	private val col = database.mongo.getCollection<PingGroup>(name)

	suspend fun get(id: String) =
		col.findOne(PingGroup::_id eq id)

	suspend fun set(pingGroup: PingGroup) =
		col.save(pingGroup)

	suspend fun getAll(guild: Snowflake, allowAny: Boolean): List<PingGroup> {
		val guildEquals = PingGroup::guildId eq guild
		if (allowAny) return col.find(guildEquals).toList()
		return col.find(and(guildEquals, PingGroup::canSelfSubscribe eq true)).toList()
	}

	suspend fun getByUser(guild: Snowflake, user: Snowflake): List<PingGroup> {
		val guildEquals = PingGroup::guildId eq guild
		return col.find(and(guildEquals, PingGroup::users contains user)).toList()
	}

	suspend fun delete(id: String) =
		col.deleteOne(PingGroup::_id eq id)

	companion object : Collection("ping-groups")
}
