/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.database.migrations

import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.exists
import org.litote.kmongo.setValue
import org.quiltmc.community.database.entities.Suggestion

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
