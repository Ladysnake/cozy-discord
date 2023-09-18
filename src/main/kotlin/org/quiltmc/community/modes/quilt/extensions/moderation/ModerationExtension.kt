/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(ExperimentalTime::class, DoNotChain::class)

package org.quiltmc.community.modes.quilt.extensions.moderation

import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.annotations.DoNotChain
import com.kotlindiscord.kord.extensions.checks.isNotBot
import com.kotlindiscord.kord.extensions.commands.application.slash.EphemeralSlashCommandContext
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.components.forms.ModalForm
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.time.TimestampType
import com.kotlindiscord.kord.extensions.time.toDiscord
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.authorId
import com.kotlindiscord.kord.extensions.utils.dm
import com.kotlindiscord.kord.extensions.utils.removeTimeout
import com.kotlindiscord.kord.extensions.utils.scheduling.Scheduler
import com.soywiz.korio.async.toChannel
import dev.kord.common.entity.*
import dev.kord.common.entity.optional.optional
import dev.kord.core.behavior.ban
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Member
import dev.kord.core.entity.Role
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.core.event.interaction.ModalSubmitInteractionCreateEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.UserMessageCreateBuilder
import dev.kord.rest.builder.message.create.actionRow
import dev.kord.rest.builder.message.create.allowedMentions
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.builder.message.modify.embed
import dev.kord.rest.json.request.ChannelModifyPatchRequest
import dev.kord.rest.request.RestRequestException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.koin.core.component.inject
import org.quiltmc.community.*
import org.quiltmc.community.database.collections.InvalidMentionsCollection
import org.quiltmc.community.database.collections.ServerSettingsCollection
import org.quiltmc.community.database.collections.UserRestrictionsCollection
import org.quiltmc.community.database.entities.InvalidMention
import org.quiltmc.community.database.entities.InvalidMention.Type.ROLE
import org.quiltmc.community.database.entities.InvalidMention.Type.USER
import org.quiltmc.community.database.entities.UserRestrictions
import org.quiltmc.community.database.getSettings
import org.quiltmc.community.modes.quilt.extensions.rotatinglog.MessageLogExtension
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

class ModerationExtension(
	private val enabledModules: List<Module> = Module.allSupported,
	private val modReportChannel: Snowflake? = null,
) : Extension() {
	private val logger = KotlinLogging.logger {}

	override val name = "moderation"

	private val scheduler = Scheduler()

	private val invalidMentions: InvalidMentionsCollection by inject()

	private val settings: ServerSettingsCollection by inject()

	private val userRestrictions: UserRestrictionsCollection by inject()

	internal val recentlyBannedUsers: MutableMap<Snowflake, User> = mutableMapOf()

	private var idActionCommand = "`/id-action`"

	override suspend fun setup() {
		if (Module.PURGE in enabledModules) {
			ephemeralSlashCommand(::PurgeArguments) {
				name = "purge"
				description = "Purge a number of messages from a channel"

				check { hasBaseModeratorRole() }

				action {
					val channel = arguments.channel ?: channel.asChannel()
					val checkUsers = arguments.user != null
					// checked in verifier
					val targetUser = arguments.user as Member?
					if (channel !is GuildMessageChannel) {
						respond {
							content = "This command can only target a text channel, and ${channel.mention} is not one."
						}
						return@action
					}
					val deletedMessageList = mutableListOf<Snowflake>()
					val messages = channel.getMessagesBefore(channel.lastMessage!!.id).toChannel()
					for (message in messages) {
						if (message.data.flags.value?.contains(MessageFlag.Ephemeral) == true) {
							continue // skip ephemeral messages
						}
						if (!checkUsers || message.author == targetUser) {
							deletedMessageList.add(message.data.id)
						}

						if (deletedMessageList.size >= arguments.amount) {
							break
						}
					}
					channel.bulkDelete(deletedMessageList, reason = "Purge command by ${user.softMention()}")

					respond {
						content = "Purged ${deletedMessageList.size} messages."
					}
				}
			}
		}
		if (Module.CHANNEL_SLOWMODE in enabledModules) {
			ephemeralSlashCommand(::SlowModeArguments) {
				name = "slowmode"
				description = "Sets/disables a channel's slowmode."

				check { hasBaseModeratorRole() }

				action(::slowmode)
			}
		}
		if (Module.LIMIT_SPAM in enabledModules) {
			// map of user ids to the most recent message ids
			val recentMessages = mutableMapOf<Snowflake, MutableList<Snowflake>>()
			event<MessageCreateEvent> {
				check { isNotBot() } // oh man my bot is going crazy
				check { notHasBaseModeratorRole() } // mods can spam :pineapple:

				action {
					if (event.member?.roles?.firstOrNull { it.id in MODERATOR_ROLES } != null) {
						return@action
					}
					val user = event.message.author?.id ?: return@action
					val messageId = event.message.id
					val recentMessagesForUser = recentMessages.getOrPut(user) { mutableListOf() }

					recentMessagesForUser.add(messageId)

					// remove all messages older than 1 minute
					recentMessagesForUser.removeAll {
						(it.timeMark + 60.seconds).hasPassedNow()
					}

					// also check for spam that happened in a second
					val spamCheck = recentMessagesForUser.filterNot {
						(it.timeMark + 1.seconds).hasPassedNow()
					}

					if (spamCheck.size > ABSOLUTE_MAX_PER_SECOND || spamCheck.size > ABSOLUTE_MAX_PER_MINUTE) {
						// over the limit, time the user out
						event.message.getAuthorAsMember()?.timeout(2.minutes)
						event.message.author.tryDM(event.getGuildOrNull()) {
							content = "You have been timed out for spamming in an associated server. " +
									"Please do not spam."
						}
						return@action
					}

					if (recentMessagesForUser.size > MAX_MESSAGES_PER_MINUTE) {
						event.message.delete()
						event.message.author.tryDM(event.getGuildOrNull()) {
							content = "You have exceeded the maximum amount of messages per minute. " +
									"Please wait a minute before sending another message."
						}
					}

					if (spamCheck.size > MAX_MESSAGES_PER_SECOND) {
						event.message.delete()
						event.message.author.tryDM(event.getGuildOrNull()) {
							content = "You have exceeded the maximum amount of messages per second. " +
									"Please wait a second before sending another message."
						}
					}
				}
			}

			// finally make sure the recent messages map is cleaned up on a regular basis to not use up too much memory
			var schedulerCallback: suspend () -> Unit = {}
			schedulerCallback = {
				recentMessages.forEach { (_, messages) ->
					// remove all messages older than 1 minute
					messages.removeAll {
						(it.timeMark + 60.seconds).hasPassedNow()
					}
				}

				// reschedule the callback
				scheduler.schedule(5.minutes, pollingSeconds = 10, callback = schedulerCallback)
			}
			scheduler.schedule(5.minutes, pollingSeconds = 10, callback = schedulerCallback)
		}
		if (Module.LIMIT_MENTIONING in enabledModules) {
			event<MessageCreateEvent> {
				check { failIfNot(event.message.type in listOf(MessageType.Default, MessageType.Reply)) }
				check { failIf(event.message.data.authorId == kord.selfId) }
				check { failIf(event.message.author?.isBot == true) }
				check { failIf(event.guildId == null) }
				check { notHasBaseModeratorRole() }
				check {
					failIf(
						event.getGuildOrNull()
							?.getSettings()
							?.pingTimeoutBlacklist
							?.contains(event.message.data.authorId) == true
					)
				}

				action {
					val guild = event.guildId!!

					val mentions = event.message.mentionedUserIds + event.message.mentionedRoleIds
					if (mentions.size > MAX_MENTIONS_PER_MESSAGE && MAX_MENTIONS_PER_MESSAGE != 0) {
						event.message.delete()
						event.message.author.tryDM(event.getGuildOrNull()) {
							content = "You have exceeded the maximum amount of mentions per message. " +
									"Please do not mention more than $MAX_MENTIONS_PER_MESSAGE users."
						}
						advanceTimeout(event.member!!, "mention spam")
					}

					// Other mentions check
					for (snowflake in event.message.mentionedRoleIds) {
						val mention = invalidMentions.get(snowflake)
						if (mention != null && !mention.allowsDirectMentions &&
							mention.type == ROLE && event.member?.id !in mention.exceptions &&
							event.member?.roleIds?.any { it in mention.exceptions } != true
						) {
							event.message.author.tryDM(event.getGuildOrNull()) {
								content = "You have mentioned a role that is not allowed to be mentioned. " +
										"Please do not mention roles that are not allowed to be mentioned."
							}
							advanceTimeout(event.member!!, "mentioning <@&$snowflake>")
						}
					}
					for (snowflake in event.message.mentionedUserIds) {
						val mention = invalidMentions.get(snowflake)
						if (mention != null && !mention.allowsDirectMentions &&
							mention.type == USER && event.member?.id !in mention.exceptions &&
							event.member?.roleIds?.any { it in mention.exceptions } != true
						) {
							event.message.author.tryDM(event.getGuildOrNull()) {
								content = "You have mentioned a user that is not allowed to be mentioned. " +
										"Please do not mention users that are not allowed to be mentioned."
							}
							advanceTimeout(event.member!!, "mentioning <@!$snowflake>")
						}
					}
				}
			}
			ephemeralSlashCommand {
				name = "mention-restriction"
				description = "Change the mention settings for a user or role."

				check { hasBaseModeratorRole() }

				ephemeralSubCommand(::MentionArguments) {
					name = "edit"
					description = "Add or edit mention restrictions."

					check { hasBaseModeratorRole() }

					action {
//					val guild = getGuild()?.asGuild() ?: return@action
						if (arguments.mentionable is Role && arguments.allowReplyMentions == true) {
							throw DiscordRelayedException("You cannot allow reply mentions for a role.")
						}

						val mentionable = arguments.mentionable
						val id = mentionable.id
						val type = when (mentionable) {
							is Role -> ROLE
							is User -> USER
							else -> error("Unknown mentionable type (or somehow \"@everyone\" was selected?)")
						}

						fun b2s(b: Boolean?) = when (b) {
							true -> "Enabled"
							false -> "Disabled"
							else -> "Unchanged"
						}

						val mention = when (mentionable) {
							is Role -> mentionable.mention
							is User -> mentionable.mention
							else -> error("Unknown mentionable type (or somehow \"@everyone\" was selected?)")
						}

						val invalidMention = invalidMentions.get(id)
							?: run {
								val newMention = InvalidMention(id, type)
								if (arguments.allowDirectMentions != null) {
									newMention.allowsDirectMentions = arguments.allowDirectMentions!!
								}
								if (arguments.allowReplyMentions != null) {
									newMention.allowsReplyMentions = arguments.allowReplyMentions!!
								}

								invalidMentions.set(newMention)

								respond {
									content = "Mention settings for $mention have been created: " +
											"Direct mentions: ${b2s(newMention.allowsDirectMentions)}, " +
											"Reply mentions: ${b2s(newMention.allowsReplyMentions)}"
								}
								return@action
							}

						invalidMention.allowsDirectMentions =
							arguments.allowDirectMentions ?: invalidMention.allowsDirectMentions

						invalidMention.allowsReplyMentions =
							arguments.allowReplyMentions ?: invalidMention.allowsReplyMentions

						invalidMentions.set(invalidMention)

						respond {
							content = "Mention settings for $mention have been updated: " +
									"Direct mentions: ${b2s(arguments.allowDirectMentions)}, " +
									"Reply mentions: ${b2s(arguments.allowReplyMentions)}"
						}
					}
				}

				ephemeralSubCommand(::RemoveMentionsArguments) {
					name = "remove"
					description = "Remove mention restrictions."

					check { hasBaseModeratorRole() }

					action {
						invalidMentions.delete(arguments.mentionable.id)

						respond {
							content = "Mention settings for ${arguments.mentionable.mention} have been removed."
						}
					}
				}

				ephemeralSubCommand(::MentionExceptionArguments) {
					name = "change-exception"
					description = "Add/remove a user or role from a mention exception list."

					check { hasBaseModeratorRole() }

					action {
						if (arguments.user.id == guild?.id) {
							respond {
								content = "**Error**: You cannot allow @everyone to be exempt from a rule."
								allowedMentions {  }
							}
							return@action
						}
						val invalidMention = invalidMentions.get(arguments.mentionable.id) ?: InvalidMention(
							arguments.mentionable.id,
								when (arguments.mentionable) {
								is Role -> ROLE
								is User -> USER
								else -> error("Unknown mentionable type (or somehow \"@everyone\" was selected?)")
							},
							allowsDirectMentions = true,
							allowsReplyMentions = true
						)

						when (arguments.action) {
							MentionResult.ADD -> invalidMention.addException(arguments.user.id)
							MentionResult.REMOVE -> invalidMention.removeException(arguments.user.id)
						}

						invalidMentions.set(invalidMention)

						respond {
							content = "Mention exception list for ${arguments.mentionable.mention} has been updated."
						}
					}
				}

				ephemeralSubCommand<RequiredUser>({ RequiredUser("The user to blacklist") }) {
					check { hasBaseModeratorRole() }

					name = "allow-massping"
					description = "Blacklist a user from ping timeouts, allowing them to ping as many people as they want."

					action {
						val settings = guild?.getSettings() ?: return@action

						settings.pingTimeoutBlacklist.add(arguments.user.id)
						settings.save()

						respond {
							content = "User ${arguments.user.mention} has been blacklisted from ping timeouts."
						}
					}
				}

				ephemeralSubCommand<RequiredUser>({ RequiredUser("The user to remove from the blacklist") }) {
					check { hasBaseModeratorRole() }

					name = "disallow-massping"
					description = "Remove a user from the ping timeout blacklist."

					action {
						val settings = guild?.getSettings() ?: return@action

						settings.pingTimeoutBlacklist.remove(arguments.user.id)
						settings.save()

						respond {
							content = "User ${arguments.user.mention} has been removed from the ping timeout blacklist."
						}
					}
				}
			}
		}
		if (Module.USER_MANAGEMENT in enabledModules) {
			ephemeralSlashCommand(::BanArguments) {
				name = "ban"
				description = "Ban a user from the server for a specified amount of time."

				check { hasBaseModeratorRole() }

				action(::beanUser)
			}
			ephemeralSlashCommand(::TimeoutArguments) {
				name = "timeout"
				description = "Timeout a user from the server for a specified amount of time."

				check { hasBaseModeratorRole() }

				action(::timeout)
			}
			@Suppress("USELESS_CAST") // Kordex needs @OverloadResolutionByLambdaReturnType
			ephemeralSlashCommand({ RequiresReason("The user to kick") } as () -> RequiresReason) {
				name = "kick"
				description = "Kick a user from the server."

				check { hasBaseModeratorRole() }

				action {
					val user = arguments.user

					val guild = getGuild()!!.asGuild()
					val member = guild.getMember(user.id)

					try {
						user.dm {
							content = "You have been kicked from ${guild.name} for the following reason:\n\n" +

									arguments.reason
						}

						reportToModChannel(guild.asGuild()) {
							description = "Kicked ${user.mention}"
							field {
								name = "Reason"
								value = arguments.reason
							}
						}
					} catch (e: RestRequestException) {
						reportToModChannel(guild.asGuild()) {
							description = "Kicked ${user.mention}"
							field {
								name = "Reason"
								value = arguments.reason
							}

							field {
								name = " "
								value = "Failed to DM user."
							}
						}
					}

					member.kick(arguments.reason)

					respond {
						content = "Kicked ${user.softMention()} (${user.id})."
					}
				}
			}
			ephemeralSlashCommand(::NoteArguments) {
				name = "note"
				description = "Add a note to a user, message, or both, for only moderators to see."

				check { hasBaseModeratorRole() }

				action {
					val user = arguments.user
					val message = arguments.messageId?.let {
						getReportingChannel(guild?.asGuild())
							?.asChannelOf<GuildMessageChannel>(guild!!.id)
							?.getMessageOrNull(it)
							?: bot.findExtension<MessageLogExtension>()
								?.rotators
								?.flatMap { (_, rotator) -> rotator.channels }
								?.firstNotNullOfOrNull { channel -> channel.getMessageOrNull(it) }
					}

					val note = arguments.note
					val currentTime = Clock.System.now().toDiscord(TimestampType.Default)

					if (message != null) {
						val embed = message.embeds.firstOrNull()
						message.edit {
							if (embed == null) {
								embed {
									title = "Added notes"

									note(note, this@action.user.mention)
								}
							} else {
								embed {
									copyFrom(embed)

									fields.firstOrNull { it.name == "Notes" }?.let {
										// field exists, append to it
										it.value = """
											$it

											Note added by ${this@action.user.mention} at $currentTime:
											> $note
										""".trimIndent()
									} ?: note(note, this@action.user.mention)
								}
							}
						}
					} else if (user != null) {
						reportToModChannel(guild?.asGuild()) {
							title = "User note"
							description = """
								This is auto-generated as a start for notes for ${user.mention}.
								Future additions should be added with `/note` and specifying `message-id`
							""".trimIndent()

							note(note, this@action.user.mention)
						}
					} else {
						reportToModChannel(guild?.asGuild()) {
							title = "Note"
							description = """
								This is auto-generated as a start for notes with no user or previous action.
								Future additions should be added with `/note` and specifying `message-id`
							""".trimIndent()

							note(note, this@action.user.mention)
						}
					}

					respond {
						content = "Note added."
					}
				}
			}
			ephemeralSlashCommand(::AdvanceTimeoutArguments) {
				name = "advance-timeout"
				description = "Advance the timeout / tempban of a user."

				check { hasBaseModeratorRole() }
				check { inLadysnakeGuild() }

				action {
					val member = arguments.user.asMemberOrNull(guild!!.id) ?: run {
						respond {
							content = "**Error:** User must be in the guild."
						}
						return@action
					}

					val reason = "Manual advancement by ${user.mention}: ${arguments.reason}"

					advanceTimeout(member, arguments.reason)

					respond {
						content = "Timeout / tempban advanced."
					}
				}
			}

			ephemeralSlashCommand(::ActionArguments) {
				name = "id-action"
				description = "Perform an action on a user by their ID."

				check { hasBaseModeratorRole() }

				action {
					val endTime = when (val length = arguments.length) {
						-1L -> null
						0L -> Instant.DISTANT_FUTURE
						else -> Clock.System.now() + length.seconds
					}
					val discordString = endTime?.toDiscord(TimestampType.Default) ?: "Removed"

					when (arguments.action) {
						"ban" -> {
							if (endTime == null) {
								val ban = guild!!.getBanOrNull(arguments.user)
								if (ban == null) {
									respond {
										content = "**Error:** User is not banned."
									}
								} else {
									guild!!.unban(ban.userId)
									respond {
										content = "Unbanned ${ban.user.mention}."
									}
								}

								return@action
							}

							val restriction = UserRestrictions(
								arguments.user,
								guild!!.id,
								true,
								endTime,
							)

							restriction.save()

							guild!!.ban(arguments.user) {
								reason = arguments.reason
								deleteMessageDuration = arguments.banDeleteDays.days
							}

							reportToModChannel(guild?.asGuild()) {
								title = "Banned id: ${arguments.user}"
								field {
									name = "Reason"
									value = arguments.reason
								}
								field {
									name = "Length"
									value = when (arguments.length) {
										0L -> "Permanent"
										-1L -> "Removed ban"
										else -> discordString
									}
								}
								field {
									name = "User information"
									value = """
										ID: `${arguments.user}`
										Current name: `${event.kord.getUser(arguments.user)?.tag}`
										Mention: <@!${arguments.user}>
									""".trimIndent()
								}
								field {
									name = "Responsible moderator"
									value = user.mention
								}
							}

							respond {
								content = "Banned ${arguments.user} (<@!${arguments.user}>)."
							}
						}
						"timeout" -> {
							if (endTime == null) {
								val restriction = userRestrictions.get(arguments.user)
								if (restriction != null) {
									userRestrictions.remove(arguments.user)
								}

								guild!!.getMemberOrNull(arguments.user)?.removeTimeout(arguments.reason)

								return@action
							}
							// since they're very likely not in the guild, we'll save an entry to the database
							val restriction = UserRestrictions(
								arguments.user,
								guild!!.id,
								false, // they're not banned, they're just timed out
								endTime,
							)

							// If they are in the guild, time them out.
							guild!!.getMemberOrNull(arguments.user)?.timeoutUntil(endTime, arguments.reason)

							restriction.save()

							reportToModChannel(guild?.asGuild()) {
								title = "Timed out id: ${arguments.user}"
								field {
									name = "Reason"
									value = arguments.reason
								}
								field {
									name = "Length"
									value = when (arguments.length) {
										0L -> "Permanent"
										-1L -> "Removed timeout"
										else -> discordString
									}
								}
								field {
									name = "User information"
									value = """
										ID: `${arguments.user}`
										Current name: `${event.kord.getUser(arguments.user)?.tag}`
										Mention: <@!${arguments.user}>
									""".trimIndent()
								}
								field {
									name = "Responsible moderator"
									value = user.mention
								}
							}

							respond {
								content = "Timed out ${arguments.user} (<@!${arguments.user}>)."
							}
						}
					}
				}
			}

			event<ButtonInteractionCreateEvent> {
				check {
					failIfNot { event.interaction.componentId.startsWith("mod:ban-sharing:") }
				}

				action {
					val (_, _, infractionId) = event.interaction.componentId.split(":")

					event.interaction.modal("Ban sharing", "mod:ban:$infractionId") {
						actionRow {
							textInput(TextInputStyle.Paragraph, "desc", "Description") {
								placeholder =
									"What did this user do? You have 4000 characters and markdown formatting available."
								required = true
							}

							textInput(TextInputStyle.Short, "img", "Image URL") {
								placeholder = "This is optional, but it can help explain the situation."
								required = false
							}
						}
					}
				}
			}

			event<ModalSubmitInteractionCreateEvent> {
				check {
					failIfNot { event.interaction.modalId.startsWith("mod:ban:") }
				}

				action {
					val deferred = event.interaction.deferEphemeralResponse()
					val (_, infractionId) = event.interaction.modalId.split(":")
					val restriction = userRestrictions.get(Snowflake(infractionId))!!
					val description = event.interaction.textInputs["desc"]!!.value!!
					val image = event.interaction.textInputs["img"]?.value

					val (id, token) = BAN_SHARING_WEBHOOK_URL!!.split("/")
						.takeLast(2)

					val user = kord.getUser(restriction._id)!!
					val guild = kord.getGuild(restriction.guildId)

					if (restriction.returningBanTime == null) {
						deferred.respond {
							content = "There was an issue with this ban. Please contact a developer."
						}
						return@action
					}

					kord.rest.webhook.executeWebhook(Snowflake(id), token) {
						embed {
							author {
								name = "(Sent by ${event.interaction.user.mention}, via Rtuuy)"
							}
							title = "${guild.name} (via Rtuuy)"
							this.description = description

							field {
								name = "User ID"
								value = user.id.toString().code()
								inline = true
							}

							field {
								name = "User Tag"
								value = user.mention.code()
								inline = true
							}

							field {
								name = "Ban Duration"
								value = if (restriction.returningBanTime == Instant.DISTANT_FUTURE) {
									"Permanent"
								} else {
									(restriction.returningBanTime!! - Clock.System.now()).toString()
								}
								inline = true
							}

							timestamp = Clock.System.now()

							footer {
								text = "Rtuuy is the Ladysnake and Rattiest Gang bot"
							}

							if (image != null) {
								this.image = image
							}
						}
					}

					deferred.respond {
						content = "Ban report sent."
					}
				}
			}

			event<ReadyEvent> {
				action {
					kord.getGlobalApplicationCommands(false).collect {
						if (it.name == "id-action") {
							idActionCommand = "</id-action:${it.id}>"
						}
					}
				}
			}

			@Suppress("MagicNumber")
			scheduler.schedule(5, repeat = true) {
				val timedOutIds = userRestrictions.getAll()
					.filter { !it.isBanned && it.returningBanTime != null }
					.map { it to kord.getGuildOrNull(it.guildId)?.getMemberOrNull(it._id) }
					.filter { it.second != null }
					.map { it.first to it.second!! }

				timedOutIds.forEach { (restriction, member) ->
					val currentlyDisabled = member.communicationDisabledUntil
					val requestedDisabled = restriction.returningBanTime!!
					val now = Clock.System.now()

					// remove the return time on the restriction if it's already past
					if (requestedDisabled < now) {
						restriction.returningBanTime = null
						restriction.save()
						return@forEach
					}

					// 1 minute offset to allow the bot to re-time out before the current timeout expires
					if (currentlyDisabled == null || currentlyDisabled - 1.minutes < now) {
						member.edit {
							val remainingTime = requestedDisabled - now
							@Suppress("MagicNumber") // Timeouts max at 28 days
							communicationDisabledUntil = now + min(remainingTime, 28.days)
						}
					}
				}

				val bannedUsers = userRestrictions.getAll()
					.filter { it.isBanned && it.returningBanTime!! <= Clock.System.now() }

				bannedUsers.forEach {
					val userId = it._id
					val guild = kord.getGuildOrNull(it.guildId)!!

					// sanity check
					if (guild.getBanOrNull(userId) == null) {
						logger.warn { "User $userId was attempted to be unbanned, even though they already are" }
						userRestrictions.remove(userId) // remove the restriction that shouldn't be there anyway
						return@forEach
					}

					guild.unban(userId)
					userRestrictions.remove(userId) // remove the restriction
				}
			}
		}

		logger.info {
			"Loaded ${slashCommands.size} commands and " +
			"${slashCommands.sumOf { it.subCommands.size }} sub-commands."
		}
	}

	private suspend fun getReportingChannel(guild: Guild? = null) = modReportChannel
		?: guild?.channels?.firstOrNull { it.name == "moderation-log" }?.id
		?: guild?.id?.let { settings.get(it)?.getConfiguredLogChannel() }?.id
		?: settings.getLadysnake()?.getConfiguredLogChannel()?.id

	private suspend inline fun reportToModChannel(
		guild: Guild?,
		text: String = "",
		embed: EmbedBuilder.() -> Unit = {}
	) {
		val channel = getReportingChannel(guild) ?: return

		// weird hack to get around kmongo bug
		val msg = kord.rest.channel.createMessage(channel) {
			this.embed {
				if (text.isNotBlank()) {
					title = "Mod log"
					description = text
				} else {
					embed(this)
				}

				timestamp = Clock.System.now()
			}
		}

		kord.rest.channel.editMessage(channel, msg.id) {
			this.embed {
				copyFrom(msg.embeds.first())

				field {
					name = "Log ID"
					value = "This field is used for adding notes to this log message.".italic()
					value += "\n" + msg.id.toString()
				}
			}
		}
	}

	private suspend inline fun slowmode(context: EphemeralSlashCommandContext<SlowModeArguments, *>, ignored: ModalForm?) {
		val channel = context.arguments.channel ?: context.channel.asChannel()
		if (channel !is GuildMessageChannel) {
			context.respond {
				content = "This command can only target a text channel which is not a thread," +
						" and ${channel.mention} is not one."
			}
			return
		}
		val slowmode = context.arguments.waitTime
		// a bit of a hack to attempt to bypass a bug with kmongo
		kord.rest.channel.patchChannel(
			channel.id,
			ChannelModifyPatchRequest(rateLimitPerUser = slowmode.seconds.optional()),
			reason = "Slowmode set by ${context.user}"
		)
		reportToModChannel(context.guild?.asGuild()) {
			title = "Slowmode set"
			description =
				"Slowmode for ${channel.mention} was set to $slowmode seconds by ${context.user.softMention()}."
		}
	}

	private suspend fun beanUser(context: EphemeralSlashCommandContext<BanArguments, *>, ignored: ModalForm?) {
		if (context.guild == null) {
			throw DiscordRelayedException("This command can only be used in a guild.")
		}

		val user = context.arguments.user
		val member = user.asMemberOrNull(context.guild!!.id)

		val reason = context.arguments.reason
		val length = context.arguments.length

		val webhookUrl = BAN_SHARING_WEBHOOK_URL

		if (member == null) {
			if (length == -1L) {
				// Unban
				val restriction = userRestrictions.get(user.id)
				if (restriction == null) {
					// try unbanning anyway
					context.guild!!.unban(user.id)
				} else {
					// set restriction end time to now so they'll be unbanned at next check
					restriction.returningBanTime = Clock.System.now()
					restriction.save()
				}

				reportToModChannel(context.guild?.asGuild()) {
					title = "User unbanned"
					description = "User ${user.mention} was unbanned by ${context.user.softMention()}."
				}

				context.respond {
					content = "User ${user.mention} was unbanned."
				}
			} else {
				context.respond {
					content = "User ${user.mention} is not in this guild. Try again with the $idActionCommand command."
				}
			}
			return
		}

		val restriction = UserRestrictions(
			member.id,
			context.guild!!.id,
			length >= 0,
			// Instant.DISTANT_FUTURE is January 1, 100,000 which truly is quite distant in the future
			if (length > 0) Clock.System.now() + length.seconds else Instant.DISTANT_FUTURE,
		)

		val returnTime = restriction.returningBanTime!!.toDiscord(TimestampType.Default)

		try {
			user.dm {
				content = "You have been banned from ${context.guild!!.asGuild().name} " +
						"until $returnTime for the following reason:\n\n" +

						context.arguments.reason
			}

			reportToModChannel(context.guild?.asGuild()) {
				description = "Banned ${user.mention}"
				field {
					name = "Reason"
					value = context.arguments.reason
				}
				field {
					name = "Length"
					value = when (length) {
						0L -> "Permanent"
						-1L -> "Unbanned"
						else -> "$length seconds (until $returnTime)"
					}
				}
				field {
					name = "Responsible moderator"
					value = context.user.mention
				}
			}
		} catch (e: RestRequestException) {
			reportToModChannel(context.guild?.asGuild()) {
				description = "Banned ${user.mention}"
				field {
					name = "Reason"
					value = context.arguments.reason
				}
				field {
					name = "Length"
					value = when (length) {
						0L -> "Permanent"
						-1L -> "Unbanned"
						else -> "$length seconds (until $returnTime)"
					}
				}
				field {
					name = "Responsible moderator"
					value = context.user.mention
				}

				field {
					name = " "
					value = "Failed to DM user."
				}
			}
		}

		if (restriction.isBanned) {
			recentlyBannedUsers[restriction._id] = context.user.asUser()

			// ban the user (the restriction just was created)
			member.ban {
				this.reason = reason
				deleteMessageDuration = context.arguments.daysToDelete.days
			}
		}

		// add the restriction to the database
		userRestrictions.set(restriction)

		context.respond {
			content = "Banned ${user.softMention()} (${user.id})."
			if (webhookUrl != null) {
				actionRow {
					interactionButton(ButtonStyle.Primary, "mod:ban-sharing:${restriction._id}") {
						label = "Report to Community Collab"
					}
				}
			}
		}
	}

	private suspend fun timeout(context: EphemeralSlashCommandContext<TimeoutArguments, *>, ignored: ModalForm?) {
		val user = context.arguments.user
		val member = user.asMember(context.guild!!.id)

		val reason = context.arguments.reason
		val length = context.arguments.length
		val endTime = Clock.System.now() + length.seconds

		// using discord's *NEW* built-in timeout functionality
		member.edit {
			communicationDisabledUntil = if (length > 0) {
				Clock.System.now() + length.seconds
			} else {
				null
			}

			this.reason = reason
		}

		val restriction = userRestrictions.get(user.id) ?: UserRestrictions(
			member.id,
			context.guild!!.id,
			false,
			null,
		)
		restriction.returningBanTime = endTime
		restriction.save()

		val returnTime = endTime.toDiscord(TimestampType.Default)

		try {
			user.dm {
				content = "You have been timed out from ${context.guild!!.asGuild().name} " +
						"until $returnTime for the following reason:\n\n" +

						context.arguments.reason
			}

			reportToModChannel(context.guild?.asGuild()) {
				title = "Timeout"
				description = "Timed out ${user.mention}"
				field {
					name = "Reason"
					value = context.arguments.reason
				}
				field {
					name = "Length"
					value = when (length) {
						-1L -> "Permanent"
						0L -> "Unbanned"
						else -> "$length seconds (until $returnTime)"
					}
				}
				field {
					name = "Responsible moderator"
					value = context.user.mention
				}
			}
		} catch (e: RestRequestException) {
			reportToModChannel(context.guild?.asGuild()) {
				title = "Timeout"
				description = "Timed out ${user.mention}"
				field {
					name = "Reason"
					value = context.arguments.reason
				}
				field {
					name = "Length"
					value = when (length) {
						-1L -> "Permanent" // this shouldn't happen with the traditional command but ok
						0L -> "Removed timeout"
						else -> "$length seconds (until $returnTime)"
					}
				}
				field {
					name = "Responsible moderator"
					value = context.user.mention
				}

				field {
					name = " "
					value = "Failed to DM user."
				}
			}
		}

		context.respond {
			content = "Timed out ${user.softMention()} (${user.id})."
		}
	}

	private suspend fun User?.tryDM(guild: Guild? = null, builder: UserMessageCreateBuilder.() -> Unit) {
		if (this != null) {
			try {
				this.getDmChannel().createMessage(builder)
			} catch (e: RestRequestException) {
				reportToModChannel(guild) {
					title = "DM failed"
					description = "Failed to send DM to $mention. (the user likely has DMs disabled)"
				}
			}
		} else {
			reportToModChannel(guild) {
				title = "DM failed"
				description = "Failed to send DM to null user."
			}
		}
	}

	private fun EmbedBuilder.note(noteText: String, modMention: String) {
		field {
			name = "Notes"
			value = """
				Note added by $modMention at ${Clock.System.now().toDiscord(TimestampType.Default)}:
				> $noteText
			""".trimIndent()
		}
	}

	@Suppress("MagicNumber")
	private suspend fun advanceTimeout(member: Member, reason: String?) {
		val restrictions = userRestrictions.get(member.id) ?: UserRestrictions(member.id, member.guildId)
		restrictions.previousTimeouts.add(Clock.System.now())
		restrictions.previousTimeouts.removeIf { it < (Clock.System.now() - 90.days) }
		val count = restrictions.previousTimeouts.size
		val lengthOfRestriction = when (count) {
			1 -> 1.minutes
			2 -> 2.minutes
			3 -> 5.minutes
			4 -> 10.minutes
			5 -> 30.minutes
			6 -> 1.hours
			7 -> 2.hours
			8 -> 4.hours
			9 -> 8.hours
			10 -> 24.hours
			11 -> 2.days
			12 -> 3.days
			13 -> 7.days
			14 -> 14.days
			15 -> 28.days // max timeout length
			16 -> 1.days // beginning of tempbans
			17 -> 2.days
			18 -> 3.days
			19 -> 7.days
			20 -> 14.days
			21 -> 28.days
			22 -> 30.days * 2 // 2 months
			23 -> 30.days * 3 // 3 months
			24 -> 30.days * 6 // 6 months
			25 -> 365.days	// 1 year
			else -> 365.days * 3 // 3 years, max tempban length because people shouldn't be banned forever usually
								 // plus they already got bans for 5 years so i think that's enough
		}
		val returnTime = Clock.System.now() + lengthOfRestriction
		if (count <= 15) {
			member.edit {
				this.reason = reason

				communicationDisabledUntil = returnTime
			}
			restrictions.returningBanTime = returnTime
		} else {
			// tempbans begin
			restrictions.isBanned = true
			restrictions.returningBanTime = returnTime

			member.ban {
				this.reason = reason
				deleteMessageDuration = ZERO
			}
		}

		restrictions.save()

		reportToModChannel(member.getGuild()) {
			title = if (count <= 15) "Timeout" else "Tempban"
			description = if (count <= 15)  {
				"Timed out ${member.mention} (${member.id})."
			} else {
				"Temporarily Banned ${member.mention} (${member.id})."
			}
			if (reason != null) {
				field {
					name = "Reason"
					value = reason
					inline = true
				}
			}
			field {
				name = "Return"
				value = returnTime.toDiscord(TimestampType.Default) + " / " +
						returnTime.toDiscord(TimestampType.RelativeTime)
				inline = true
			}
		}
	}

	enum class Module {
		PURGE,
		USER_MANAGEMENT,
		LIMIT_SPAM,
		BAN_SHARING, // TODO implement
		CHANNEL_SLOWMODE,
		LIMIT_MENTIONING,
		AUDIT_LOG_WATCHER,
		;

		companion object {
			val allSupported = values().filterNot {
				it in listOf(BAN_SHARING) // the only one that's not supported yet
			}
		}
	}
}
