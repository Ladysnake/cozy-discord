/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.database

import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.quiltmc.community.database.collections.MetaCollection
import org.quiltmc.community.database.entities.Meta
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findParameterByName

const val FILE_TEMPLATE = "migrations/v{VERSION}.bson"

object Migrations : KoinComponent {
    private val logger = KotlinLogging.logger { }

    val db: Database by inject()
    val metaColl: MetaCollection by inject()

    suspend fun migrate() {
        var meta = metaColl.get()

        if (meta == null) {
            meta = Meta(0)

            metaColl.set(meta)
        }

        var currentVersion = meta.version

        logger.info { "Current database version: v$currentVersion" }

        val migrations = Migrations::class.declaredFunctions
            .filter { it.name.startsWith("v") } // make sure it's a migration
            .filter { it.name.substring(1).toInt() > currentVersion } // newer than current version
            .filter { it.findParameterByName("db") != null } // with a db parameter
            .sortedBy { it.name.substring(1).toInt() } // and sorted by version number

        for (function in migrations) {
            @Suppress("TooGenericExceptionCaught")
            try {
                if (function.parameters[0].kind == KParameter.Kind.INSTANCE) {
                    function.callSuspend(Migrations, db.mongo)
                } else {
                    function.callSuspend(db.mongo)
                }

                logger.info { "Migrated to ${function.name}" }
            } catch (t: Throwable) {
                logger.error(t) { "Failed to migrate database to ${function.name}" }

                throw t
            }
            currentVersion = function.name.substring(1).toInt() // remove 'v' prefix
        }

        if (currentVersion != meta.version) {
            meta = meta.copy(version = currentVersion)

            metaColl.set(meta)

            logger.info { "Finished database migrations." }
        }
    }
}
