/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(ExperimentalTime::class)

package org.quiltmc.community.modes.quilt.extensions.settings

import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.checks.anyGuild
import com.kotlindiscord.kord.extensions.checks.types.CheckContext
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.enumChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.optionalEnumChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.application.slash.group
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.types.edit
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.suggestStringMap
import dev.kord.common.entity.ArchiveDuration
import dev.kord.common.entity.Permission
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.asChannelOfOrNull
import dev.kord.core.entity.channel.Category
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.core.event.guild.GuildCreateEvent
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.koin.core.component.inject
import org.quiltmc.community.*
import org.quiltmc.community.database.collections.GlobalSettingsCollection
import org.quiltmc.community.database.collections.ServerSettingsCollection
import org.quiltmc.community.database.collections.UserFlagsCollection
import org.quiltmc.community.database.entities.GlobalSettings
import org.quiltmc.community.database.entities.ServerSettings
import org.quiltmc.community.database.entities.UserFlags
import org.quiltmc.community.database.enums.LadysnakeServerType
import org.quiltmc.community.modes.quilt.extensions.converters.sealedObjectChoice
import org.quiltmc.community.modes.quilt.extensions.rotatinglog.CategoryRotator
import org.quiltmc.community.modes.quilt.extensions.rotatinglog.MessageLogExtension
import org.quiltmc.community.modes.quilt.extensions.suggestions.AutoRemoval
import org.quiltmc.community.modes.quilt.extensions.suggestions.SuggestionStatus
import org.quiltmc.community.modes.quilt.extensions.suggestions.defaultAutoRemovals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

// TODO: Implement these settings in other parts of the bot, and add logging

class SettingsExtension : Extension() {
	override val name: String = "settings"

	private val logger = KotlinLogging.logger { }

	private val globalSettings: GlobalSettingsCollection by inject()
	private val serverSettings: ServerSettingsCollection by inject()
	private val userFlags: UserFlagsCollection by inject()

	private val messageLogExtension get() = bot.findExtension<MessageLogExtension>()

	override suspend fun setup() {
		if (globalSettings.get() == null) {
			logger.info { "Creating initial global settings entry" }

			GlobalSettings().save()
		}

		event<GuildCreateEvent> {
			action {
				val settings = serverSettings.get(event.guild.id)

				if (settings == null) {
					logger.info { "Creating settings entry for guild: ${event.guild.name} (${event.guild.id.value})" }

					serverSettings.set(ServerSettings(event.guild.id))
				} else if (settings.leaveServer) {
					logger.info { "Leaving guild, as configured: ${event.guild.name} (${event.guild.id.value})" }

					delay(2.seconds)

					event.guild.leave()
				}
			}
		}

		GUILDS.forEach { guildId ->
			ephemeralSlashCommand {
				name = "config"
				description = "Manage your bot settings"

				guild(guildId)

				ephemeralSubCommand {
					name = "get"
					description = "Show your settings"

					action {
						val flags = userFlags.get(user.id) ?: UserFlags(user.id)

						respond {
							embed {
								title = "Your Settings"
								color = DISCORD_BLURPLE

								description = buildString {
									append("**Auto publish:** ")

									if (flags.autoPublish) {
										appendLine("Enabled")
									} else {
										appendLine("Disabled")
									}

									append("**Sync nicknames:** ")

									if (flags.syncNicks) {
										appendLine("Enabled")
									} else {
										appendLine("Disabled")
									}

									trim()
								}
							}
						}
					}
				}

				ephemeralSubCommand(booleanFlag("auto-publish announcement messages")) {
					name = "auto-publish"
					description = "Configure the automatic publishing of announcement messages"

					action {
						val flags = userFlags.get(user.id) ?: UserFlags(user.id)

						flags.autoPublish = arguments.value
						flags.save()

						respond {
							content = "Auto-publishing **" + if (flags.autoPublish) {
								"enabled"
							} else {
								"disabled"
							} + "**."
						}
					}
				}

				ephemeralSubCommand(booleanFlag("sync nickname between servers")) {
					name = "nick-sync"
					description = "Set whether your nickname should be synced between Quilt servers"

					action {
						val flags = userFlags.get(user.id) ?: UserFlags(user.id)

						flags.syncNicks = arguments.value
						flags.save()

						respond {
							content = "Nickname sync **" + if (flags.syncNicks) {
								"enabled"
							} else {
								"disabled"
							} + "**."
						}
					}
				}
			}
		}

		ephemeralSlashCommand {
			name = "global-config"
			description = "Global Cozy configuration commands"

			allowInDms = false

			check { isAdminOrHasOverride() }

			ephemeralSubCommand {
				name = "get"
				description = "Retrieve Cozy's global configuration"

				action {
					val settings = globalSettings.get()!!

					respond {
						embed {
							settings.apply(this)
						}
					}
				}
			}

			ephemeralSubCommand(::InviteArg) {
				name = "appeals-invite"
				description = "Set or get the invite code used to invite banned users to the appeals server"

				action {
					val settings = globalSettings.get()!!

					if (arguments.inviteCode == null) {
						respond {
							content = "**Current invite:** https://discord.gg/${settings.appealsInvite}"
						}

						return@action
					}

					var code = arguments.inviteCode!!

					if ("/" in code) {
						code = code.split("/").last()
					}

					settings.appealsInvite = code
					settings.save()

					respond {
						content = "**New invite set:** https://discord.gg/${settings.appealsInvite}"
					}
				}
			}

			ephemeralSubCommand(::TokenArg) {
				name = "github-token"
				description = "Set the GitHub login token used by the GitHub commands"

				action {
					val settings = globalSettings.get()!!

					settings.githubToken = arguments.loginToken
					settings.save()

					respond {
						content = "**GitHub login token set successfully**"
					}
				}
			}

			ephemeralSubCommand(::TopMessageChannelArg) {
				name = "github-log-channel"
				description = "Set or get the channel used for logging GitHub command actions"

				check { inYoutube() }

				action {
					val settings = globalSettings.get()!!

					if (arguments.channel == null) {
						respond {
							content = "**Current GitHub log channel:** <#${settings.githubLogChannel}>"
						}

						return@action
					}

					settings.githubLogChannel = arguments.channel!!.id
					settings.save()

					respond {
						content = "**New GitHub log channel set:** <#${settings.githubLogChannel}>"
					}
				}
			}

			ephemeralSubCommand(::GuildArg) {
				name = "add-guild"
				description = "Mark a server as an official Ladysnake server"

				action {
					val settings = globalSettings.get()!!
                    val server = arguments.server

					if (server.id in settings.ladysnakeGuilds) {
						respond {
							content = ":x: **${server.name}** is already marked as an official Ladysnake guild."
						}

						return@action
					}

					settings.ladysnakeGuilds.add(server.id)
					settings.save()

					respond {
						content = "**${server.name}** marked as an official Ladysnake guild."
					}
				}
			}

			ephemeralSubCommand(::GuildSnowflakeArg) {
				name = "remove-guild"
				description = "Unmark a server as an official Ladysnake server"

				action {
					val settings = globalSettings.get()!!

					if (arguments.serverId !in settings.ladysnakeGuilds) {
						respond {
							content = ":x: `${arguments.serverId.value}` is not marked as an official Ladysnake guild."
						}

						return@action
					}

					settings.ladysnakeGuilds.remove(arguments.serverId)
					settings.save()

                    respond {
                        content = "`${arguments.serverId.value}` is no longer marked as an official Ladysnake guild."
                    }
                }
            }

            ephemeralSubCommand(::SuggestionAutoRemovalArg) {
                name = "add"
                description = "Add a rule for automatic responses"

                check { hasBaseModeratorRole() }

                action {
                    val rule = AutoRemoval(
                        arguments.id,
                        arguments.regex,
                        arguments.result,
                        arguments.reason
                    )

                    val settings = globalSettings.get() ?: GlobalSettings()

                    settings.suggestionAutoRemovals.add(rule)
                    settings.save()

					respond {
						content = "Auto removal rule added: ${rule.id}"
                    }
                }
            }

            ephemeralSubCommand(::SuggestionAutoRemovalEditArg) {
                name = "edit"
                description = "Edit an existing rule"

                check { hasBaseModeratorRole() }

                action {
                    val rule = globalSettings.get()?.suggestionAutoRemovals?.find { it.id == arguments.id }

                    if (rule == null) {
                        respond {
                            content = ":x: No auto removal rule with ID: `${arguments.id}`"
                        }

                        return@action
                    }

                    val newRule = AutoRemoval(
                        arguments.id,
                        arguments.regex?.toRegex(RegexOption.IGNORE_CASE) ?: rule.regex,
                        arguments.result ?: rule.status,
                        arguments.reason ?: rule.reason
                    )

                    val settings = globalSettings.get() ?: GlobalSettings()

                    settings.suggestionAutoRemovals.remove(rule)
                    settings.suggestionAutoRemovals.add(newRule)

                    settings.save()

                    respond {
                        content = "Auto rule edited: $newRule"
                    }
                }
            }

            ephemeralSubCommand(::SuggestionAutoRemovalDeleteArg) {
                name = "delete"
                description = "Delete an existing rule"

                check { hasBaseModeratorRole() }

                action {
                    val settings = globalSettings.get() ?: GlobalSettings()

                    val rule = settings.suggestionAutoRemovals.find { it.id == arguments.id }

                    if (rule == null) {
                        respond {
                            content = ":x: No rule with ID: `${arguments.id}`"
                        }

                        return@action
                    }

                    settings.suggestionAutoRemovals.remove(rule)

                    settings.save()
				}
			}
		}

		ephemeralSlashCommand {
			name = "server-config"
			description = "Server-specific Cozy configuration commands"

			allowInDms = false

			check { anyGuild() }
			check {
				any(
                    { hasPermissionInMainGuild(Permission.Administrator) },
                    { failIfNot { event.interaction.user.id in OVERRIDING_USERS } }
                )
            }

            group("general") {
                this@SettingsExtension.logger.error {
					"This shouldn't be required to be in a group! Kordex is a bit funky though"
                }

                description = "General commands which don't fit in any other category"

                ephemeralSubCommand(::OptionalGuildSnowflakeArg) {
                    name = "get"
                    description = "Retrieve Cozy's server configuration"

                    action {
                        val context = CheckContext(event, getLocale())

                        if (arguments.serverId != null) {
                            with(context) {
                                any(
                                    { hasPermissionInMainGuild(Permission.Administrator) },
                                    { failIfNot { event.interaction.user.id in OVERRIDING_USERS } }
                                )
                            }

                            if (!context.passed) {
                                respond {
                                    content =
                                        ":x: Only Ladysnake community managers can modify settings for other servers."
                                }

                                return@action
                            }
                        }

                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        }

                        if (settings == null) {
                            respond {
                                content = "Unknown guild ID: `${arguments.serverId?.value}`"
                            }

                            return@action
                        }

                        respond {
                            embed {
                                settings.apply(
                                    this,
                                    arguments.serverId != null ||
                                            settings.ladysnakeServerType != null ||
                                            guild?.id == MAIN_GUILD
                                )
                            }
                        }
                    }
                }

                ephemeralSubCommand(::PrefixServerArg) {
                    name = "command-prefix"
                    description = "Configure Cozy's command prefix"

                    action {
                        val context = CheckContext(event, getLocale())

                        if (arguments.serverId != null) {
                            with(context) {
                                any(
                                    { hasPermissionInMainGuild(Permission.Administrator) },
                                    { failIfNot { event.interaction.user.id in OVERRIDING_USERS } }
                                )
                            }

                            if (!context.passed) {
                                respond {
                                    content =
                                        ":x: Only Ladysnake community managers can modify settings for other servers."
                                }

                                return@action
                            }
                        }

                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        }

                        if (settings == null) {
                            respond {
                                content = "Unknown guild ID: `${arguments.serverId?.value}`"
                            }

                            return@action
                        }

                        settings.commandPrefix = arguments.prefix
                        settings.save()

                        respond {
                            content = "**Command prefix set:** `${settings.commandPrefix}`"
                        }
                    }
                }

                ephemeralSubCommand(::TopMessageChannelGuildArg) {
                    name = "cozy-log-channel"
                    description = "Configure the channel Cozy should send log messages to"

                    action {
                        val context = CheckContext(event, getLocale())

                        if (arguments.serverId != null) {
                            with(context) {
                                any(
                                    { hasPermissionInMainGuild(Permission.Administrator) },
                                    { failIfNot { event.interaction.user.id in OVERRIDING_USERS } }
                                )
                            }

                            if (!context.passed) {
                                respond {
                                    content =
                                        ":x: Only Ladysnake community managers can modify settings for other servers."
                                }

                                return@action
                            }
                        }

                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        }

                        if (settings == null) {
                            respond {
                                content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                            }

                            return@action
                        }

                        if (arguments.channel == null) {
                            respond {
                                content = "**Current Cozy logging channel:** <#${settings.cozyLogChannel?.value}>"
                            }

                            return@action
                        }

                        val channel = event.kord.getChannelOf<TopGuildMessageChannel>(arguments.channel!!.id)!!

                        if (channel.guildId != settings._id) {
                            respond {
                                content =
                                    ":x: That channel doesn't belong to the guild with ID: `${settings._id.value}`"
                            }

                            return@action
                        }

                        settings.cozyLogChannel = channel.id
                        settings.save()

                        respond {
                            content = "**Cozy logging channel set:** ${channel.mention}"
                        }
                    }
                }

                ephemeralSubCommand(::TopMessageChannelGuildArg) {
                    name = "filter-log-channel"
                    description = "Configure the channel Cozy should send filter log messages to"

                    action {
                        val context = CheckContext(event, getLocale())

                        if (arguments.serverId != null) {
                            with(context) {
                                any(
                                    { hasPermissionInMainGuild(Permission.Administrator) },
                                    { failIfNot { event.interaction.user.id in OVERRIDING_USERS } }
                                )
                            }

                            if (!context.passed) {
                                respond {
                                    content = ":x: Only Quilt community managers can modify settings for other servers."
                                }

                                return@action
                            }
                        }

                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        }

                        if (settings == null) {
                            respond {
                                content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                            }

                            return@action
                        }

                        if (arguments.channel == null) {
                            respond {
                                content = "**Current Cozy filter logging channel:** " +
                                        "<#${settings.filterLogChannel?.value}>"
                            }

                            return@action
                        }

                        val channel = event.kord.getChannelOf<TopGuildMessageChannel>(arguments.channel!!.id)!!

                        if (channel.guildId != settings._id) {
                            respond {
                                content = ":x: That channel doesn't belong to the guild with ID: " +
                                        "`${settings._id.value}`"
                            }

                            return@action
                        }

                        settings.filterLogChannel = channel.id
                        settings.save()

                        respond {
                            content = "**Cozy filter logging channel set:** ${channel.mention}"
                        }
                    }
                }

                ephemeralSubCommand(::CategoryGuildArg) {
                    name = "message-log-category"
                    description = "Configure the category Cozy should use for message logs"

                        action {
                            val context = CheckContext(event, getLocale())

                            if (arguments.serverId != null) {
                                with(context) {
                                    any(
                                        { hasPermissionInMainGuild(Permission.Administrator) },
                                        { failIfNot { event.interaction.user.id in OVERRIDING_USERS } }
                                    )
                                }

                                if (!context.passed) {
                                    respond {
                                        content = ":x: Only Ladysnake community managers " +
                                                "can modify settings for other servers."
                                    }

                                    return@action
                                }
                            }

                            val settings = if (arguments.serverId == null) {
                                serverSettings.get(guild!!.id)
                            } else {
                                serverSettings.get(arguments.serverId!!)
                            }

                            if (settings == null) {
                                respond {
                                    content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                                }

                                return@action
                            }

                            if (arguments.category == null) {
                                respond {
                                    content = "**Current message log category:** " +
                                            "<#${settings.messageLogCategory?.value}>"
                                }

                                return@action
                            }

                            val category = event.kord.getChannelOf<Category>(arguments.category!!.id)!!

                            if (category.guildId != settings._id) {
                                respond {
                                    content =
                                        ":x: That category doesn't belong to the guild with ID: `${settings._id.value}`"
                                }

                                return@action
                            }

                            settings.messageLogCategory = category.id
                            settings.save()

                            respond {
                                content = "**Message log category set:** ${category.mention}"
                            }

                            event.kord.launch {
                                val logChannel = settings.getConfiguredLogChannel()
                                    ?: channel.asChannelOfOrNull<GuildMessageChannel>()
                                    ?: run {
                                        edit {
                                            content = ":x: No log channel configured. No logs can be sent."
                                        }
                                        return@launch
                                    }

                                messageLogExtension?.rotators?.getOrPut(settings._id) {
                                    CategoryRotator(category, logChannel)
                                }?.populate()
                            }
                        }
                    }

                ephemeralSubCommand(::LadysnakeServerTypeArg) {
                    name = "ladysnake-server-type"
                    description = "For Ladysnake servers: Set or remove the Ladysnake server type flag for a server"

                    check { isAdminOrHasOverride() }

                    action {
                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        }

                        if (settings == null) {
                            respond {
                                content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                            }

                            return@action
                        }

                        if (arguments.type != null) {
                            val existingServers = serverSettings.getByServerType(arguments.type).toList()

                            if (existingServers.isNotEmpty()) {
                                respond {
                                    content = ":x: The following servers are already flagged as the" +
                                            " ${arguments.type!!.readableName} server: \n\n" +

                                            existingServers.joinToString("\n") { "`${it._id}`" }
                                }

                                return@action
                            }
                        }

                        settings.ladysnakeServerType = arguments.type
                        settings.save()

                        respond {
                            content = if (settings.ladysnakeServerType == null) {
                                "**Server no longer flagged as a Ladysnake server:** `${settings._id.value}`"
                            } else {
                                "**Server flagged as the ${settings.ladysnakeServerType!!.readableName} server:** " +
                                        "`${settings._id.value}`"
                            }
                        }
                    }
                }

                ephemeralSubCommand(::ShouldLeaveArg) {
                    name = "set-leave-server"
                    description = "For Ladysnake servers: Set whether Cozy should automatically leave a server"

                    check { isAdminOrHasOverride() }

                    action {
                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        } ?: ServerSettings(guild!!.id)

                        settings.leaveServer = arguments.shouldLeave
                        settings.save()

                        respond {
                            content = if (arguments.shouldLeave) {
                                "**Server will now be left automatically:** `${settings._id.value}`"
                            } else {
                                "**Server will not left automatically:** `${settings._id.value}`"
                            }
                        }

                        if (settings.leaveServer) {
                            event.kord.getGuild(settings._id)?.leave()
                        }
                    }
                }

                ephemeralSubCommand(::ArchiveDurationArg) {
                    name = "set-default-archive-duration"
                    description = "Set the default duration for thread archive lengths"

                    check { isAdminOrHasOverride() }

                    action {
                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        } ?: ServerSettings(guild!!.id)

                        settings.defaultThreadLength = arguments.duration
                        settings.save()

                        respond {
                            content = "**Default archive duration set to:** `${settings.defaultThreadLength}`"
                        }
                    }
                }
            }

            group("thread-only") {
                description = "Commands for managing thread-only channels"

                ephemeralSubCommand(::TopMessageChannelGuildArg) {
                    name = "add"
                    description = "For Ladysnake servers: Add a channel to the list of " +
                            "channels that are only for threads"

                    check { isAdminOrHasOverride() }

                    action {
                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        } ?: ServerSettings(guild!!.id)

                        settings.threadOnlyChannels.add(arguments.channel?.id ?: channel.id)
                        settings.save()

                        respond {
                            content = "**Channel added to thread-only channels:** `${settings._id.value}`"
                        }
                    }
                }

                ephemeralSubCommand(::TopMessageChannelGuildArg) {
                    name = "remove"
                    description = "For Ladysnake servers: Remove a channel from the list of " +
                            "channels that are only for threads"

                    check { isAdminOrHasOverride() }

                    action {
                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        } ?: ServerSettings(guild!!.id)

                        settings.threadOnlyChannels.remove(arguments.channel?.id ?: channel.id)
                        settings.save()

                        respond {
                            content = "**Channel removed from thread-only channels:** `${settings._id.value}`"
                        }
                    }
                }
            }

            group("mod-roles") {
                description = "Commands for managing mod roles"

                ephemeralSubCommand(::RoleServerArg) {
                    name = "add"
                    description = "Add a role that should be given moderator permissions"

                    action {
                        val context = CheckContext(event, getLocale())

                        if (arguments.serverId != null) {
                            context.hasPermissionInMainGuild(Permission.Administrator)

                            if (!context.passed) {
                                respond {
                                    content =
                                        ":x: Only Ladysnake community managers can modify settings for other servers."
                                }

                                return@action
                            }
                        }

                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        }

                        if (settings == null) {
                            respond {
                                content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                            }

                            return@action
                        }

                        if (arguments.role.guildId != settings._id) {
                            respond {
                                content = ":x: That role doesn't belong to the guild with ID: `${settings._id.value}`"
                            }

                            return@action
                        }

                        if (arguments.role.id in settings.moderatorRoles) {
                            respond {
                                content = ":x: That role is already marked as a moderator role"
                            }

                            return@action
                        }

                        settings.moderatorRoles.add(arguments.role.id)
                        settings.save()

                        respond {
                            content = "Moderator role added: ${arguments.role.mention}"
                        }
                    }
                }

                ephemeralSubCommand(::RoleServerArg) {
                    name = "remove"
                    description = "Remove a configured moderator role"

                    action {
                        val context = CheckContext(event, getLocale())

                        if (arguments.serverId != null) {
                            context.hasPermissionInMainGuild(Permission.Administrator)

                            if (!context.passed) {
                                respond {
                                    content =
                                        ":x: Only Ladysnake community managers can modify settings for other servers."
                                }

                                return@action
                            }
                        }

                        val settings = if (arguments.serverId == null) {
                            serverSettings.get(guild!!.id)
                        } else {
                            serverSettings.get(arguments.serverId!!)
                        }

                        if (settings == null) {
                            respond {
                                content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                            }

                            return@action
                        }

                        if (arguments.role.guildId != settings._id) {
                            respond {
                                content = ":x: That role doesn't belong to the guild with ID: `${settings._id.value}`"
                            }

                            return@action
                        }

                        if (arguments.role.id !in settings.moderatorRoles) {
                            respond {
                                content = ":x: That role is not marked as a moderator role"
                            }

                            return@action
                        }

                        settings.moderatorRoles.remove(arguments.role.id)
                        settings.save()

                        respond {
                            content = "Moderator role removed: ${arguments.role.mention}"
                        }
                    }
                }
            }
        }
	}

	fun booleanFlag(desc: String): () -> BooleanFlag = {
		BooleanFlag(desc)
	}

	inner class BooleanFlag(desc: String) : Arguments() {
		val value by boolean {
			name = "value"
			description = "Whether to $desc"
		}
	}

	inner class InviteArg : Arguments() {
		val inviteCode by optionalString {
			name = "invite-code"
			description = "Invite code to use"
		}
	}

	inner class TokenArg : Arguments() {
		val loginToken by string {
			name = "login-token"
			description = "Login token to use"
		}
	}

	inner class TopMessageChannelArg : Arguments() {
		val channel by optionalChannel {
			name = "channel"
			description = "Channel to use"

			validate {
				val channel = value

				if (channel != null) {
					val kord = getKoin().get<Kord>()

					if (kord.getChannelOf<TopGuildMessageChannel>(channel.id) == null) {
						fail("${channel.mention} isn't a guild message channel")
					}
				}
			}
		}
	}

	inner class TopMessageChannelGuildArg : Arguments() {
		val channel by optionalChannel {
			name = "channel"
			description = "Channel to use"

			validate {
				val channel = value

				if (channel != null) {
					val kord = getKoin().get<Kord>()

					if (kord.getChannelOf<TopGuildMessageChannel>(channel.id) == null) {
						fail("${channel.mention} isn't a guild message channel")
					}
				}
			}
		}

		val serverId by optionalSnowflake {
			name = "server"
			description = "Server ID, if not the current one"
		}
	}

	inner class GuildArg : Arguments() {
		val server by guild {
			name = "server"
			description = "Server ID to use"
		}
	}

	inner class GuildSnowflakeArg : Arguments() {
		val serverId by snowflake {
			name = "server"
			description = "Server ID to use"
		}
	}

	inner class OptionalGuildSnowflakeArg : Arguments() {
		val serverId by optionalSnowflake {
			name = "server"
			description = "Server ID, if not the current one"
		}
	}

	inner class CategoryGuildArg : Arguments() {
		val category by optionalChannel {
			name = "category"
			description = "Category to use"

			validate {
				val channel = value

				if (channel != null) {
					val kord = getKoin().get<Kord>()

					if (kord.getChannelOf<Category>(channel.id) == null) {
						fail("${channel.mention} isn't a category")
					}
				}
			}
		}

		val serverId by optionalSnowflake {
			name = "server"
			description = "Server ID, if not the current one"
		}
	}

	inner class LadysnakeServerTypeArg : Arguments() {
		val type by optionalEnumChoice<LadysnakeServerType> {
			name = "type"
			description = "Ladysnake server type"

			typeName = "Server type"
		}

		val serverId by optionalSnowflake {
			name = "server"
			description = "Server ID, if not the current one"
		}
	}

	inner class ShouldLeaveArg : Arguments() {
		val shouldLeave by boolean {
			name = "should-leave"
			description = "Whether Cozy should leave the server automatically"
		}

		val serverId by optionalSnowflake {
			name = "server"
			description = "Server ID, if not the current one"
		}
	}

	inner class PrefixServerArg : Arguments() {
		val prefix by string {
			name = "prefix"
			description = "Command prefix to set"
		}

		val serverId by optionalSnowflake {
			name = "server"
			description = "Server ID, if not the current one"
		}
	}

	inner class RoleServerArg : Arguments() {
		val role by role {
			name = "role"
			description = "Role to add/remove"
		}

		val serverId by optionalSnowflake {
			name = "server"
			description = "Server ID, if not the current one"
		}
	}

	inner class ArchiveDurationArg : Arguments() {
        val duration by sealedObjectChoice<ArchiveDuration> {
            name = "duration"
            description = "Length of time before threads automatically archive"
        }

        val serverId by optionalSnowflake {
            name = "server"
            description = "Server ID, if not the current one"
        }
	}

	inner class SuggestionAutoRemovalArg : Arguments() {
        val id by string {
            name = "id"
            description = "ID of the auto-response object to add"
        }

        val regex by string {
            name = "regex"
            description = "Regex to match"
        }

        val result by enumChoice<SuggestionStatus> {
            name = "result"
            description = "Automatic result to set"
            typeName = "bad kordex"
        }

        val reason by string {
            name = "reason"
            description = "Reason for the result"
        }
	}

	inner class SuggestionAutoRemovalEditArg : Arguments() {
        val id by string {
            name = "id"
            description = "ID of the auto-response object to modify"

            validate {
                failIf("ID must link to a valid auto response!") {
                    val list = getKoin().get<GlobalSettingsCollection>().get()?.suggestionAutoRemovals
                        ?: defaultAutoRemovals

                    list.none { it.id == value }
                }
            }

            autoComplete {
                val list = getKoin().get<GlobalSettingsCollection>().get()?.suggestionAutoRemovals
                    ?: defaultAutoRemovals

                val map = list.associate {
                    val key = it.id
                    val arg = "${it.status} - ${it.reason}"

                    @Suppress("MagicNumber")
                    (if (arg.length > 100) arg.substring(0..96) + "..." else arg) to key
                }

                suggestStringMap(map)
            }
        }

        val regex by optionalString {
            name = "regex"
            description = "Regex to match"
        }

        val result by optionalEnumChoice<SuggestionStatus> {
            name = "result"
            description = "Automatic result to set"
            typeName = "bad kordex"
        }

        val reason by optionalString {
            name = "reason"
            description = "Reason for the result"
        }
	}

	inner class SuggestionAutoRemovalDeleteArg : Arguments() {
        val id by string {
            name = "id"
            description = "ID of the auto-response object to delete"

            validate {
                failIf("ID must link to a valid auto response!") {
                    val list = getKoin().get<GlobalSettingsCollection>().get()?.suggestionAutoRemovals
                        ?: defaultAutoRemovals

                    list.none { it.id == value }
                }
            }

            autoComplete {
                val list = getKoin().get<GlobalSettingsCollection>().get()?.suggestionAutoRemovals
                    ?: defaultAutoRemovals

                val map = list.associate {
                    val key = it.id
                    val arg = "${it.status} - ${it.reason}"

                    @Suppress("MagicNumber")
                    (if (arg.length > 100) arg.substring(0..96) + "..." else arg) to key
                }

                suggestStringMap(map)
            }
        }
	}
}
