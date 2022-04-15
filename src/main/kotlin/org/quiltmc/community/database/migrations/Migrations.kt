/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.database.migrations

import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.ReplaceOneModel
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.quiltmc.community.LADYSNAKE_GUILD
import org.quiltmc.community.LADYSNAKE_SUGGESTION_CHANNEL
import org.quiltmc.community.database.collections.*
import org.quiltmc.community.database.entities.*
import org.quiltmc.community.modes.quilt.extensions.filtering.MatchTarget

object Migrations {
    suspend fun v1(db: CoroutineDatabase) {
        db.createCollection(ServerSettingsCollection.name)
        db.createCollection(SuggestionsCollection.name)
    }

    suspend fun v2(db: CoroutineDatabase) {
        db.createCollection("collab-server-settings")

        with(db.getCollection<Suggestion>(SuggestionsCollection.name)) {
            updateMany(
                Suggestion::thread exists false,
                setValue(Suggestion::thread, null),
            )

            updateMany(
                Suggestion::threadButtons exists false,
                setValue(Suggestion::threadButtons, null),
            )
        }
    }

    suspend fun v3(db: CoroutineDatabase) {
        db.createCollection(OwnedThreadCollection.name)

        val suggestions = db.getCollection<Suggestion>(SuggestionsCollection.name)
        val ownedThreads = db.getCollection<OwnedThread>(OwnedThreadCollection.name)

        val documents = mutableListOf<ReplaceOneModel<OwnedThread>>()

        suggestions.find().consumeEach {
            if (it.thread != null) {
                documents.add(
                    replaceOne(
                        OwnedThread::_id eq it.thread!!,

                        OwnedThread(
                            it.thread!!,
                            it.owner,
                            LADYSNAKE_GUILD
                        ),

                        replaceUpsert()
                    )
                )
            }
        }

        if (documents.isNotEmpty()) {
            ownedThreads.bulkWrite(requests = documents, BulkWriteOptions().ordered(false))
        }
    }

    suspend fun v4(db: CoroutineDatabase) {
        db.createCollection(TeamCollection.name)
    }

    suspend fun v5(db: CoroutineDatabase) {
        db.createCollection(FilterCollection.name)
    }

    suspend fun v6(db: CoroutineDatabase) {
        db.createCollection(FilterEventCollection.name)
    }

    suspend fun v7(db: CoroutineDatabase) {
        db.dropCollection("collab-server-settings")
        db.createCollection(GlobalSettingsCollection.name)
    }

    suspend fun v8(db: CoroutineDatabase) {
        with(db.getCollection<OwnedThread>(OwnedThreadCollection.name)) {
            updateMany(
                OwnedThread::preventArchiving exists false,
                setValue(OwnedThread::preventArchiving, false),
            )
        }
    }

    suspend fun v9(db: CoroutineDatabase) {
        db.createCollection(UserFlagsCollection.name)
    }

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

    /**
     * An equivalent to upstream's v10 migration, but we already have a
     * v10 migration, so we have to add a new migration instead.
     */
    suspend fun v12(db: CoroutineDatabase) {
        with(db.getCollection<FilterEntry>(FilterCollection.name)) {
            updateMany(
                FilterEntry::note exists false,
                setValue(FilterEntry::note, null),
            )
        }
    }

    /**
     * Targets upstream's v11 migration.
     */
    suspend fun v13(db: CoroutineDatabase) {
        with(db.getCollection<Suggestion>(SuggestionsCollection.name)) {
            updateMany(
                Suggestion::githubIssue exists false,
                setValue(Suggestion::githubIssue, null),
            )
        }
    }

    suspend fun v14(db: CoroutineDatabase) {
        val suggestions = db.getCollection<Suggestion>()
        suggestions.updateMany(
            Suggestion::problem exists false,
            setValue(Suggestion::problem, null)
        )
        suggestions.updateMany(
            Suggestion::solution exists false,
            setValue(Suggestion::solution, null)
        )
    }

    /**
     * Targets upstream's v12 migration.
     */
    suspend fun v15(db: CoroutineDatabase) {
        with(db.getCollection<FilterEntry>(FilterCollection.name)) {
            updateMany(
                FilterEntry::matchTarget exists false,
                setValue(FilterEntry::matchTarget, MatchTarget.MESSAGE),
            )
        }
    }

    suspend fun v16(db: CoroutineDatabase) {
        with(db.getCollection<UserRestrictions>(UserRestrictionsCollection.name)) {
            updateMany(
                UserRestrictions::lastProgressiveTimeoutLength exists false,
                setValue(UserRestrictions::lastProgressiveTimeoutLength, 0)
            )
        }
    }

    suspend fun v17(db: CoroutineDatabase) {
        with(db.getCollection<InvalidMention>(InvalidMentionsCollection.name)) {
            updateMany(
                InvalidMention::exceptions exists false,
                setValue(InvalidMention::exceptions, mutableListOf())
            )
        }
    }

    /**
     * Targets upstream's v13 and v14 migrations.
     */
    suspend fun v18(db: CoroutineDatabase) {
        db.createCollection(TagsCollection.name)

        with(db.getCollection<TagEntity>(TagsCollection.name)) {
            updateMany(
                TagEntity::image exists false,
                setValue(TagEntity::image, null),
            )
        }
    }
}
