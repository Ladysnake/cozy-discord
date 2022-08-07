/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.extensions.Extension
import dev.kord.common.entity.Permission.UseApplicationCommands
import dev.kord.core.behavior.edit
import dev.kord.rest.request.RestRequestException
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

            val everyone = guild?.getEveryoneRole()

            try {
                everyone?.edit {
                    permissions = everyone.permissions + UseApplicationCommands
                }
            } catch (e: RestRequestException) {
                // ignore
            }

            guild?.roles?.collect {
                try {
                    it.edit {
                        permissions = it.permissions + UseApplicationCommands
                    }
                } catch (e: RestRequestException) {
                    // ignore
                }
            }
        }
	}
}
