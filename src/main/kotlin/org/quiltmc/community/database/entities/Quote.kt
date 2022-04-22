/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.database.entities

import kotlinx.serialization.Serializable
import org.quiltmc.community.database.Entity

@Serializable
data class Quote(
    override val _id: Int,
    val quote: String,
    val author: String,
) : Entity<Int>
