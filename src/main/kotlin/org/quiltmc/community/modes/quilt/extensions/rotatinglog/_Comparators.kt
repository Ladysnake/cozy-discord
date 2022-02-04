/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.rotatinglog

import dev.kord.common.entity.optional.Optional
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

internal fun <T : Any> KProperty<T>.comparator(other: T) =
    ReadOnlyProperty<Any?, Optional<T>> { _, _ ->
        val old = getter.call()
        if (old == other) Optional() else Optional.Value(other)
    }

@JvmName("nullableComparator")
internal fun <T : Any> KProperty<T?>.comparator(other: T?) =
    ReadOnlyProperty<Any?, Optional<T?>> { _, _ ->
        val old = getter.call()
        if (old == other) Optional() else Optional(other)
    }

internal fun List<Optional<*>>.allMissing() = all { it is Optional.Missing }

internal fun allMissing(vararg optional: Optional<*>) = optional.all { it is Optional.Missing }

internal fun <T : Any> T.allMissing(generator: T.() -> List<KProperty<Optional<*>>>): Boolean {
    return generator()
        .map { it.getter.call() }
        .allMissing()
}
