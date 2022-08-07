/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.rotatinglog

import dev.kord.common.entity.optional.Optional
import dev.kord.core.entity.Role

class RoleDiff(val new: Role, val old: Role) {
	val name by new.data::name.comparator(old.data.name)
	val color by new.data::color.comparator(old.data.color)
	val hoisted by new.data::hoisted.comparator(old.data.hoisted)
	val icon by new.data::icon.comparator(old.data.icon)
	val unicodeEmoji by new.data::unicodeEmoji.comparator(old.data.unicodeEmoji)
	val position by new.data::position.comparator(old.data.position)
	val permissions by new.data::permissions.comparator(old.data.permissions)
	val managed by new.data::managed.comparator(old.data.managed)
	val mentionable by new.data::mentionable.comparator(old.data.mentionable)
	val tags by new.data::tags.comparator(old.data.tags)

	val isIdentical: Boolean
        get() =
            name is Optional.Missing &&
            color is Optional.Missing &&
            hoisted is Optional.Missing &&
            icon is Optional.Missing &&
            unicodeEmoji is Optional.Missing &&
            position is Optional.Missing &&
            permissions is Optional.Missing &&
            managed is Optional.Missing &&
            mentionable is Optional.Missing &&
            tags is Optional.Missing
}
