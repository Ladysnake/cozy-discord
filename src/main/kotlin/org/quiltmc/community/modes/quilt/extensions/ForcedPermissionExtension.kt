/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.extensions.Extension
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.core.behavior.edit
import org.koin.core.component.inject
import org.quiltmc.community.GUILDS
import org.quiltmc.community.database.collections.GlobalSettingsCollection

class ForcedPermissionExtension : Extension() {
    override val name = "forced-permissions"

    private val globalSettings: GlobalSettingsCollection by inject()

    override suspend fun setup() {
        val settingsGuilds = globalSettings.get()?.ladysnakeGuilds ?: emptySet()
        val envGuilds = GUILDS

        val guilds = (envGuilds + settingsGuilds).toSet()

        guilds.forEach { guildId ->
            val guild = kord.getGuild(guildId)

            guild?.getEveryoneRoleOrNull()?.edit {
                permissions = if (permissions != null) {
                    permissions!! + Permission.UseSlashCommands
                } else {
                    Permissions(Permission.UseSlashCommands)
                }
            }

            guild?.roles?.collect {
                it.edit {
                    permissions = if (permissions != null) {
                        permissions!! + Permission.UseSlashCommands
                    } else {
                        Permissions(Permission.UseSlashCommands)
                    }
                }
            }
        }
    }
}
