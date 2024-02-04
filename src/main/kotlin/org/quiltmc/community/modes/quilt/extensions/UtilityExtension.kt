/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(ExperimentalTime::class, KordPreview::class, InternalCoroutinesApi::class)

@file:Suppress("MagicNumber", "NoUnusedImports")  // Apparently Duration.Companion.seconds isn't used enough?

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.DISCORD_GREEN
import com.kotlindiscord.kord.extensions.DISCORD_RED
import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.annotations.DoNotChain
import com.kotlindiscord.kord.extensions.checks.*
import com.kotlindiscord.kord.extensions.checks.types.CheckContext
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.components.ComponentContainer
import com.kotlindiscord.kord.extensions.components.components
import com.kotlindiscord.kord.extensions.components.ephemeralButton
import com.kotlindiscord.kord.extensions.components.ephemeralStringSelectMenu
import com.kotlindiscord.kord.extensions.components.forms.ModalForm
import com.kotlindiscord.kord.extensions.extensions.*
import com.kotlindiscord.kord.extensions.i18n.SupportedLocales
import com.kotlindiscord.kord.extensions.time.TimestampType
import com.kotlindiscord.kord.extensions.time.toDiscord
import com.kotlindiscord.kord.extensions.utils.*
import com.kotlindiscord.kord.extensions.utils.scheduling.Scheduler
import dev.kord.common.annotation.KordPreview
import dev.kord.common.entity.*
import dev.kord.core.behavior.channel.*
import dev.kord.core.behavior.channel.threads.edit
import dev.kord.core.behavior.createRole
import dev.kord.core.behavior.edit
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.thread.ThreadChannel
import dev.kord.core.event.channel.thread.TextChannelThreadCreateEvent
import dev.kord.core.event.channel.thread.ThreadUpdateEvent
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.guild.MemberUpdateEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.rest.builder.message.embed
import dev.kord.rest.request.RestRequestException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.forms.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.component.inject
import org.quiltmc.community.*
import org.quiltmc.community.cozy.modules.moderation.compareTo
import org.quiltmc.community.database.collections.*
import org.quiltmc.community.database.entities.OwnedThread
import org.quiltmc.community.database.entities.PingGroup
import org.quiltmc.community.database.entities.UserFlags
import org.quiltmc.community.database.getSettings
import org.quiltmc.community.modes.quilt.extensions.suggestions.SuggestionStatus
import org.quiltmc.community.modes.quilt.extensions.suggestions.SuggestionsExtension
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

val SPEAKING_PERMISSIONS: Array<Permission> = arrayOf(
	Permission.SendMessages,
	Permission.AddReactions,
	Permission.CreatePublicThreads,
	Permission.CreatePrivateThreads,
	Permission.SendMessagesInThreads,
)

val THREAD_CHANNEL_TYPES: Array<ChannelType> = arrayOf(
	ChannelType.PublicGuildThread,
	ChannelType.PublicNewsThread,
	ChannelType.PrivateThread,
)

val TEXT_CHANNEL_TYPES: Array<ChannelType> = arrayOf(
	ChannelType.GuildText,
	ChannelType.GuildNews,
)

val STATUS_CHANNEL_ID = envOrNull("STATUS_CHANNEL")
val DELETE_DELAY = 10.seconds
val MESSAGE_EDIT_DELAY = 3.seconds
val PIN_DELETE_DELAY = 10.seconds
val THREAD_CREATE_DELETE_DELAY = 30.minutes

class UtilityExtension : Extension() {
	override val name: String = "utility"

	private val logger = KotlinLogging.logger { }
	private val privilegedRoles = listOf(
		MANAGER_ROLES,
		MODERATOR_ROLES
	).flatten()

	private val pingGroups: PingGroupCollection by inject()
	private val threads: OwnedThreadCollection by inject()
	private val userFlags: UserFlagsCollection by inject()

	private val suggestionsExtension: SuggestionsExtension? = bot.findExtension()
	private val suggestions: SuggestionsCollection? = suggestionsExtension?.suggestions
	private val threadIds = mutableMapOf<Snowflake, Snowflake>()

	private val scheduler = Scheduler()

	@OptIn(ExperimentalSerializationApi::class)
	private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "	"

		classDiscriminator = "_type"
		encodeDefaults = false
	}

	override suspend fun setup() {
		if (STATUS_CHANNEL_ID != null) {
			event<ReadyEvent> {
				action {
					val channel = kord.getChannelOf<TextChannel>(Snowflake(STATUS_CHANNEL_ID))

					channel?.createMessage {
						content = buildString {
							append("**Bot connected:** ")
							append(Clock.System.now().toDiscord(TimestampType.LongDateTime))
							append(" (")
							append(Clock.System.now().toDiscord(TimestampType.RelativeTime))
							append(")")
						}
					}
				}
			}
		}

		event<MemberUpdateEvent> {
			check { inLadysnakeGuild() }
			check { isNotBot() }

			check {
				failIf {
					event.old != null && event.member.nickname == event.old?.nickname
				}
			}

			@OptIn(DoNotChain::class)
			action {
				val flags = userFlags.get(event.member.id) ?: UserFlags(event.member.id)

				if (flags.syncNicks) {
					val guilds = getKoin().get<GlobalSettingsCollection>().get()?.ladysnakeGuilds
						?: GUILDS // in case the global settings collection is nonexistent

                    val otherGuilds = guilds.mapNotNull { kord.getGuildOrNull(it) }
                        .mapNotNull { it.getMemberOrNull(event.member.id) }
                        .filter { it.nickname != event.member.nickname } // reduce extra calls

					otherGuilds.forEach {
                        if (event.member.nickname != it.nickname) {
                            it.setNickname(
                                event.member.nickname,
                                "Synced from ${event.guild.asGuild().name}"
                            )
                        }
                    }
                }
            }
        }

		event<MessageCreateEvent> {
			check { inLadysnakeGuild() }
			check { isNotBot() }
			check { channelType(ChannelType.GuildNews) }
			check { failIf(event.message.author == null) }

			action {
				val flags = userFlags.get(event.message.author!!.id) ?: UserFlags(event.message.author!!.id)

				if (flags.autoPublish) {
					event.message.publish()
				}
			}
		}

		event<MessageCreateEvent> {
			check { failIf { event.message.type != MessageType.ChannelPinnedMessage } }
//            check { failIf { event.message.data.authorId != event.kord.selfId } }

			action {
				delay(PIN_DELETE_DELAY)

				event.message.deleteIgnoringNotFound()
			}
		}

//		event<MessageCreateEvent> {
//			check { inLadysnakeGuild() }
//			check { failIf { event.message.type != MessageType.ThreadCreated } }
//
//			action {
//				delay(THREAD_CREATE_DELETE_DELAY)
//
//				event.message.deleteIgnoringNotFound()
//			}
//		}

		event<MessageCreateEvent> {
            check { failIf { event.message.type != MessageType.ThreadCreated } }

            action {
                // discord is so very concern
                // i need to get the first message in the thread to check whether i should delete the creation message

                // oh did i mention the discord api puts the thread channel id as part of the "message reference"
                val thread = event.message.messageReference?.channel?.asChannelOf<ThreadChannel>()
                    ?: return@action

                val firstMessage = thread.messages.firstOrNull() ?: return@action

                if (firstMessage.messageReference != null && firstMessage.messageReference!!.message != null) {
                    delay(DELETE_DELAY)

                    event.message.delete("Removing thread creation message of a thread with a parent message")
                }
            }
        }

        event<TextChannelThreadCreateEvent> {
            check { inLadysnakeGuild() }
			check { failIf(event.channel.ownerId == kord.selfId) }
			check { failIf(event.channel.member != null) }  // We only want thread creation, not join
			check { failIf(event.channel.owner.asUserOrNull()?.isBot == true) }

			action {
				val owner = event.channel.owner.asUser()

				logger.info { "Thread created by ${owner.tag}" }

				val role = when (event.channel.guildId) {
					LADYSNAKE_GUILD -> event.channel.guild.getRole(LADYSNAKE_MODERATOR_ROLE)
					YOUTUBE_GUILD -> event.channel.guild.getRole(YOUTUBE_MODERATOR_ROLE)

					else -> return@action
				}

				// Work around a Discord API race condition - yes, really!
				// Specifically: "Cannot message this thread until after the post author has sent an initial message."
				delay(1.seconds)

				val message = event.channel.createMessage {
					content = "Oh hey, that's a nice thread you've got there! Let me just get the mods in on this " +
							"sweet discussion..."
				}

				event.channel.withTyping {
					delay(3.seconds)
				}

				message.edit {
					content = "Hey, ${role.mention}, you've gotta check this thread out!"
				}

				event.channel.withTyping {
					delay(3.seconds)
				}

				val threadId = threadIds.getOrPut(event.channel.guildId) {
					event.channel.guild
						.getApplicationCommands()
						.first { it.name == "thread" }
						.id
				}

				message.edit {
					content = "Welcome to your new thread, ${owner.mention}! This message is at the start of the " +
							"thread. Remember, you're welcome to use the </thread:$threadId> commands to manage " +
							"your thread as needed."
				}

				message.pin("First message in the thread.")
			}
		}

		event<ThreadUpdateEvent> {
			action {
				val channel = event.channel
				val ownedThread = threads.get(channel)

				if (channel.isArchived && ownedThread != null && ownedThread.preventArchiving) {
					channel.edit {
						archived = false
						reason = "Preventing thread from being archived."
					}
				}
			}
		}

		GUILDS.forEach { guildId ->
			ephemeralSlashCommand(::SelfTimeoutArguments) {
				name = "self-timeout"
				description = "Time yourself out for up to three days"

				allowInDms = false

				guild(guildId)

				check { inLadysnakeGuild() }

				action {
					lateinit var components: ComponentContainer

					val relative = Clock.System.now()
						.plus(arguments.duration, TimeZone.UTC)
						.toDiscord(TimestampType.RelativeTime)

					val absolute = Clock.System.now()
						.plus(arguments.duration, TimeZone.UTC)
						.toDiscord(TimestampType.LongDateTime)

					edit {
						content = "You've requested a timeout, which will end $relative (at $absolute).\n\n" +

								"This timeout will be applied as soon as you click the button below. However, please " +
								"note that **we will not be removing timeouts you set on yourself** in most " +
								"situations, even if you request it. You should avoid setting timeouts you're not " +
								"sure about.\n\n" +

								"Are you sure you'd like to apply this timeout?"

						components = components {
							ephemeralButton {
								label = "Confirm"
								style = ButtonStyle.Danger

								@OptIn(DoNotChain::class)
								action {
									member!!.asMember()
										.timeout(
											arguments.duration,
											reason = "Requested using /self-timeout"
										)

									guild?.asGuild()?.getCozyLogChannel()?.createEmbed {
										title = "Requested timeout automatically applied"
										color = DISCORD_BLURPLE

										userField(user.asUser())

										field {
											name = "Duration"
											value = arguments.duration.format(SupportedLocales.ENGLISH)
										}

										field {
											name = "Relative ending time"
											value = relative
										}

										field {
											name = "Absolute ending time"
											value = absolute
										}
									}

									respond {
										content = "Your timeout has been applied. See you $relative!"
									}

									components.cancel()
								}
							}

							ephemeralButton {
								label = "Cancel"
								style = ButtonStyle.Secondary

								action {
									respond {
										content = "Your timeout has been cancelled."
									}

									components.cancel()
								}
							}
						}
					}
				}
			}

			ephemeralMessageCommand {
				name = "Raw JSON"

				allowInDms = false

				guild(guildId)

				check { hasBaseModeratorRole() }

				action {
					val messages = targetMessages.map { it.data }
					val data = json.encodeToString(messages)

					respond {
						content = "Raw message data attached below."

						addFile(
							"message.json",
							ChannelProvider { data.byteInputStream().toByteReadChannel() }
						)
					}
				}
			}

			ephemeralMessageCommand {
				name = "Pin in thread"

				allowInDms = false

				guild(guildId)

				check { isInThread() }

				action {
					val channel = channel.asChannelOf<ThreadChannel>()
					val member = user.asMember(guild!!.id)
					val roles = member.roles.toList().map { it.id }

					if (privilegedRoles.any { it in roles }) {
						targetMessages.forEach { it.pin("Pinned by ${member.tag}") }
						edit { content = "Messages pinned." }

						return@action
					}

					if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
						respond { content = "**Error:** This is not your thread." }

						return@action
					}

					targetMessages.forEach { it.pin("Pinned by ${member.tag}") }

					edit { content = "Messages pinned." }
				}
			}

			ephemeralMessageCommand {
				name = "Unpin in thread"

				allowInDms = false

				guild(guildId)

				check { isInThread() }

				action {
					val channel = channel.asChannelOf<ThreadChannel>()
					val member = user.asMember(guild!!.id)
					val roles = member.roles.toList().map { it.id }

					if (privilegedRoles.any { it in roles }) {
						targetMessages.forEach { it.unpin("Unpinned by ${member.tag}") }
						edit { content = "Messages unpinned." }

						return@action
					}

					if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
						respond { content = "**Error:** This is not your thread." }

						return@action
					}

					targetMessages.forEach { it.unpin("Unpinned by ${member.tag}") }

					edit { content = "Messages unpinned." }
				}
			}

			ephemeralSlashCommand {
				name = "thread"
				description = "Thread management commands"

				allowInDms = false

				guild(guildId)

				check { isInThread() }

				ephemeralSubCommand {
					name = "backup"
					description = "Get all messages in the current thread, saving them into a Markdown file."

					guild(guildId)

					check { hasBaseModeratorRole() }
					check { isInThread() }

					action {
						val thread = channel.asChannelOfOrNull<ThreadChannel>()

						if (thread == null) {
							respond {
								content = "**Error:** This channel isn't a thread!"
							}

							return@action
						}

						val messageBuilder = StringBuilder()
						val formatter = DateTimeFormatter.ofPattern("dd LL, yyyy -  kk:mm:ss")

						if (thread.lastMessageId == null) {
							respond {
								content = "**Error:** This thread has no messages!"
							}

							return@action
						}

						val messages = thread.getMessagesBefore(thread.lastMessageId!!)
						val lastMessage = thread.getLastMessage()!!

						val parent = thread.parent.asChannel()

						messageBuilder.append("# Thread: ${thread.name}\n\n")
						messageBuilder.append("* **ID:** `${thread.id.value}`\n")
						messageBuilder.append("* **Parent:** #${parent.name} (`${parent.id.value}`)\n\n")

						val messageStrings: MutableList<String> = mutableListOf()

						messages.collect { msg ->
							val author = msg.author
							val builder = StringBuilder()
							val timestamp = formatter.format(
								msg.id.timestamp.toLocalDateTime(TimeZone.UTC).toJavaLocalDateTime()
							)

							if (msg.content.isNotEmpty() || msg.attachments.isNotEmpty()) {
								val authorName = author?.tag ?: msg.data.author.username

								this@UtilityExtension.logger.debug { "\nAuthor name: `$authorName`\n${msg.content}\n" }

								if (msg.type == MessageType.ChatInputCommand) {
									builder.append("🖥️ ")
								} else if (author == null) {
									builder.append("🌐 ")
								} else if (author.isBot) {
									builder.append("🤖 ")
								} else {
									builder.append("💬 ")
								}

								builder.append("**$authorName** at $timestamp (UTC)\n\n")

								if (msg.content.isNotEmpty()) {
									builder.append(msg.content.lines().joinToString("\n") { line -> "> $line" })
									builder.append("\n\n")
								}

								if (msg.attachments.isNotEmpty()) {
									msg.attachments.forEach { att ->
										builder.append("* 📄 [${att.filename}](${att.url})\n")
									}

									builder.append("\n")
								}

								messageStrings.add(builder.toString())
							}
						}

						messageStrings.reverse()

						lastMessage.let { msg ->
							val author = msg.author
							val builder = StringBuilder()
							val timestamp = formatter.format(
								msg.id.timestamp.toLocalDateTime(TimeZone.UTC).toJavaLocalDateTime()
							)

							if (msg.content.isNotEmpty() || msg.attachments.isNotEmpty()) {
								val authorName = author?.tag ?: msg.data.author.username

								if (msg.type == MessageType.ChatInputCommand) {
									builder.append("🖥️ ")
								} else if (author == null) {
									builder.append("🌐 ")
								} else if (author.isBot) {
									builder.append("🤖 ")
								} else {
									builder.append("💬 ")
								}

								builder.append("**$authorName** at $timestamp (UTC)\n\n")

								if (msg.content.isNotEmpty()) {
									builder.append(msg.content.lines().joinToString("\n") { line -> "> $line" })
									builder.append("\n\n")
								}

								if (msg.attachments.isNotEmpty()) {
									msg.attachments.forEach { att ->
										builder.append("* 📄 [${att.filename}](${att.url})\n")
									}

									builder.append("\n")
								}

								messageStrings.add(builder.toString())
							}
						}

						messageStrings.forEach(messageBuilder::append)

						respond {
							content = "**Thread backup created by ${user.mention}.**"

							addFile(
								"thread.md",
								ChannelProvider {
									messageBuilder.toString().byteInputStream().toByteReadChannel()
								}
							)
						}
					}
				}

				ephemeralSubCommand(::RenameArguments) {
					name = "rename"
					description = "Rename the current thread, if you have permission"

					check { isInThread() }

					action {
						val channel = channel.asChannelOfOrNull<ThreadChannel>()
                            ?: run {
                                respond {
                                    content = "**Error:** ${channel.mention} isn't a thread!"
                                }
                                return@action
                            }
						val member = user.asMember(guild!!.id)
						val roles = member.roles.toList().map { it.id }

						if (privilegedRoles.any { it in roles }) {
							channel.edit {
								name = arguments.name

								reason = "Renamed by ${member.tag}"
							}

							val suggestion = suggestions?.getByThread(channel.id)

                            if (suggestion != null && suggestion.status == SuggestionStatus.RequiresName) {
                                suggestion.status = SuggestionStatus.Open
                                suggestion.positiveVoters.add(member.id)
                                // because `suggestion` is non-null, `suggestions` is non-null
                                suggestions!!.set(suggestion)
                                // that also means `suggestionsExtension` is non-null
                                suggestionsExtension!!.sendSuggestion(suggestion)
                            }

                            edit { content = "Thread renamed." }

							return@action
						}

						if ((channel.ownerId != user.id && threads.isOwner(channel, user) != true)) {
							edit { content = "**Error:** This is not your thread." }

							return@action
						}

						channel.edit {
							name = arguments.name

							reason = "Renamed by ${member.tag}"
						}

						val suggestion = suggestions?.getByThread(channel.id)

                        if (suggestion != null && suggestion.status == SuggestionStatus.RequiresName) {
                            suggestion.status = SuggestionStatus.Open
                            suggestions!!.set(suggestion)
                            suggestionsExtension!!.sendSuggestion(suggestion)
                        }

                        edit { content = "Thread renamed." }
                    }
                }

				ephemeralSubCommand(::ArchiveArguments) {
					name = "archive"
					description = "Archive the current thread, if you have permission"

					check { isInThread() }

					action {
						val channel = channel.asChannelOf<ThreadChannel>()
						val member = user.asMember(guild!!.id)
						val roles = member.roles.toList().map { it.id }
						val ownedThread = threads.get(channel)

						if (privilegedRoles.any { it in roles }) {
							if (ownedThread != null) {
								ownedThread.preventArchiving = false
								threads.set(ownedThread)
							}

							channel.edit {
								this.archived = true
								this.locked = arguments.lock

								reason = "Archived by ${user.asUser().tag}"
							}

							edit {
								content = "Thread archived"

								if (arguments.lock) {
									content += " and locked"
								}

								content += "."
							}

							return@action
						}

						if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
							edit { content = "This is not your thread." }

							return@action
						}

						if (channel.isArchived) {
							edit { content = "**Error:** This channel is already archived." }

							return@action
						}

						if (arguments.lock) {
							edit { content = "**Error:** Only moderators may lock threads." }

							return@action
						}

						if (ownedThread != null && ownedThread.preventArchiving) {
							edit {
								content = "**Error:** This thread can only be archived by a moderator."
							}

							return@action
						}

						channel.edit {
							archived = true

							reason = "Archived by ${user.asUser().tag}"
						}

						edit { content = "Thread archived." }
					}
				}

				ephemeralSubCommand(::AutoLockArguments) {
					name = "set-auto-lock"
					description = "Set the auto-lock options for the current thread, if you have permission"

					check { hasBaseModeratorRole() }
					check { isInThread() }

					action {
						val channel = channel.asChannelOf<ThreadChannel>()
						val member = user.asMember(guild!!.id)
						val ownedThread = threads.get(channel)

						if (ownedThread != null) {
							if (ownedThread.preventArchiving) {
								throw DiscordRelayedException(
									"This thread is set to prevent archiving, and cannot be auto-locked."
								)
							}

							// Update, but don't remove existing values
							ownedThread.maxThreadDuration = arguments.maxTotalTime?.toDuration(TimeZone.UTC)
								?: ownedThread.maxThreadDuration
							ownedThread.maxThreadAfterIdle = arguments.maxIdleTime?.toDuration(TimeZone.UTC)
								?: ownedThread.maxThreadAfterIdle
							threads.set(ownedThread)
						} else {
							threads.set(
								OwnedThread(
									channel.id,
									channel.owner.id,
									channel.guild.id,
									false,
									arguments.maxTotalTime?.toDuration(TimeZone.UTC),
									arguments.maxIdleTime?.toDuration(TimeZone.UTC)
								)
							)
						}

						edit { content = "Auto-lock settings updated." }
					}
				}

				ephemeralSubCommand(::PinMessageArguments) {
					name = "pin"
					description = "Pin a message in this thread, if you have permission"

					check { isInThread() }

					action {
						val channel = channel.asChannelOf<ThreadChannel>()
						val member = user.asMember(guild!!.id)
						val roles = member.roles.toList().map { it.id }

						if (arguments.message.channelId != channel.id) {
							edit {
								content = "**Error:** You may only pin a message in the current thread."
							}

							return@action
						}

						if (privilegedRoles.any { it in roles }) {
							arguments.message.pin("Pinned by ${member.tag}")
							edit { content = "Message pinned." }

							return@action
						}

						if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
							edit { content = "**Error:** This is not your thread." }

							return@action
						}

						arguments.message.pin("Pinned by ${member.tag}")

						edit { content = "Message pinned." }
					}
				}

				ephemeralSubCommand(::PinMessageArguments) {
					name = "unpin"
					description = "Unpin a message in this thread, if you have permission"

					check { isInThread() }

					action {
						val channel = channel.asChannelOf<ThreadChannel>()
						val member = user.asMember(guild!!.id)
						val roles = member.roles.toList().map { it.id }

						if (arguments.message.channelId != channel.id) {
							edit {
								content = "**Error:** You may only pin a message in the current thread."
							}

							return@action
						}

						if (privilegedRoles.any { it in roles }) {
							arguments.message.unpin("Unpinned by ${member.tag}")
							edit { content = "Message unpinned." }

							return@action
						}

						if (channel.ownerId != user.id && threads.isOwner(channel, user) != true) {
							edit { content = "**Error:** This is not your thread." }

							return@action
						}

						arguments.message.unpin("Unpinned by ${member.tag}")

						edit { content = "Message unpinned." }
					}
				}

				ephemeralSubCommand {
					name = "prevent-archiving"
					description = "Prevent the current thread from archiving, if you have permission"

					guild(guildId)

					check { hasBaseModeratorRole() }
					check { isInThread() }

					action {
						val channel = channel.asChannelOf<ThreadChannel>()
						val member = user.asMember(guild!!.id)

						if (channel.isArchived) {
							channel.edit {
								archived = false
								reason = "`/thread prevent-archiving` run by ${member.tag}"
							}
						}

						val thread = threads.get(channel)

						if (thread != null) {
							if (thread.preventArchiving) {
								edit {
									content = "I'm already stopping this thread from being archived."
								}

								return@action
							}

							thread.preventArchiving = true
							threads.set(thread)
						} else {
							threads.set(
								OwnedThread(
									channel.id,
									channel.owner.id,
									channel.guild.id,
									true
								)
							)
						}

						edit { content = "Thread will no longer be archived." }

						guild?.asGuild()?.getCozyLogChannel()?.createEmbed {
							title = "Thread Persistence Enabled"
							color = DISCORD_BLURPLE

							userField(member, "Moderator")
							channelField(channel, "Thread")
						}
					}
				}

				ephemeralSubCommand(::SetOwnerArguments) {
					name = "set-owner"
					description = "Change the owner of the thread, if you have permission"

					check { isInThread() }

					action {
						val channel = channel.asChannel() as ThreadChannel
						val member = user.asMember(guild!!.id)
						val roles = member.roles.toList().map { it.id }
						var thread = threads.get(channel)

						if (thread == null) {
							thread = OwnedThread(
								_id = channel.id,
								owner = channel.ownerId,
								guild = guild!!.id,
								preventArchiving = false,
							)
						}

						val previousOwner = thread.owner

						if ((thread.owner != user.id && threads.isOwner(channel, user) != true) &&
							!privilegedRoles.any { it in roles }
						) {
							edit { content = "**Error:** This is not your thread." }
							return@action
						}

						if (thread.owner == arguments.user.id) {
							edit {
								content = "That user already owns this thread."
							}

							return@action
						}

						if (privilegedRoles.any { it in roles }) {
							thread.owner = arguments.user.id
							threads.set(thread)

							edit { content = "Updated thread owner to ${arguments.user.mention}" }

							guild?.asGuild()?.getCozyLogChannel()?.createEmbed {
								title = "Thread Owner Updated (Moderator)"
								color = DISCORD_BLURPLE

								userField(member.asUser(), "Moderator")
								userField(guild!!.getMember(previousOwner), "Previous Owner")
								userField(arguments.user, "New Owner")
								channelField(channel, "Thread")
							}
						} else {
							respond {
								embed {
									color = DISCORD_BLURPLE
									description =
										"Are you sure you want to transfer ownership to " +
												"${arguments.user.mention}? To cancel the" +
												" transfer, simply ignore this message."
								}

								components(15.seconds) {
									onTimeout {
										edit {
											embed {
												color = DISCORD_BLURPLE
												description =
													"Action timed out - no change performed"
											}

											components {
												removeAll()
											}
										}
									}

									ephemeralButton {
										label = "Yes"
										action {
											thread.owner = arguments.user.id
											threads.set(thread)

											edit {
												embed {
													color = DISCORD_BLURPLE
													description =
														"Updated thread owner to " +
																arguments.user.mention
												}

												components {
													removeAll()
												}
											}

											guild?.asGuild()?.getCozyLogChannel()?.createEmbed {
												title = "Thread Owner Updated (User)"
												color = DISCORD_BLURPLE

												userField(member.asUser(), "Previous Owner")
												userField(arguments.user, "New Owner")
												channelField(channel, "Thread")
											}
										}
									}
								}
							}
						}
					}
				}
			}

			ephemeralSlashCommand(::SayArguments) {
				name = "say"
				description = "Send a message."

				allowInDms = false

				guild(guildId)

				check { hasBaseModeratorRole() }

				action {
					val flags = arguments.flags?.split(",")?.map { it.trim().lowercase() } ?: emptyList()
                    val targetChannel = (arguments.target ?: channel.asChannel()).asChannelOf<GuildMessageChannel>()

                    val message = arguments.message
                    when {
                        ("@everyone" in message || "@here" in message) &&
                        "dangerous-mentioning-i-understand-the-consequences" !in flags -> {
                            // don't allow the message to be sent without the flag specified
                            respond {
                                embed {
                                    color = DISCORD_RED
                                    description = "You tried to mention everyone or here without the appropriate " +
                                            "flag. Please add the `dangerous-mentioning-i-understand-the-" +
                                            "consequences` flag in the flags list to bypass this check."
                                }
                            }
                            return@action
                        }
                    }

					targetChannel.createMessage(arguments.message)

					guild?.asGuild()?.getCozyLogChannel()?.createEmbed {
						title = "/say command used"
						description = arguments.message

						field {
							inline = true
							name = "Channel"
							value = targetChannel.mention
						}

                        field {
                            inline = true
                            name = "User"
                            value = user.mention
                        }

                        if (flags.isNotEmpty()) {
                            field {
                                inline = true
                                name = "Flags"
                                value = flags.joinToString(", ") {
                                    @Suppress("UseIfInsteadOfWhen")
                                    when (it) {
                                        "dangerous-mentioning-i-understand-the-consequences" -> "Mention Override"
                                        else -> "Unknown Flag ($it)"
                                    }
                                }
                            }
                        }
                    }

					edit { content = "Done!" }
				}
			}

			ephemeralSlashCommand(::MuteRoleArguments) {
				name = "fix-mute-role"
				description = "Fix the permissions for the mute role on this server."

				allowInDms = false

				guild(guildId)

				check { isAdminOrHasOverride() }

				action {
					val role = arguments.role ?: guild?.asGuild()?.roles?.firstOrNull { it.name.lowercase() == "muted" }

					if (role == null) {
						respond {
							content =
								"Unable to find a role named `Muted` - double-check the list of roles, or provide " +
										"one as an argument."
						}
						return@action
					}

					var channelsUpdated = 0

					for (channel in guild!!.channels.toList()) {
						val overwrite = channel.getPermissionOverwritesForRole(role.id)

						val allowedPerms = overwrite?.allowed
						val deniedPerms = overwrite?.denied

						val hasNonDeniedPerms =
							deniedPerms == null || SPEAKING_PERMISSIONS.any { !deniedPerms.contains(it) }

						val canDenyNonAllowedPerms = allowedPerms == null || SPEAKING_PERMISSIONS.any {
							!allowedPerms.contains(it) && deniedPerms?.contains(it) != true
						}

						if (hasNonDeniedPerms && canDenyNonAllowedPerms) {
							channel.editRolePermission(role.id) {
								SPEAKING_PERMISSIONS
									.filter { allowedPerms?.contains(it) != true }
									.forEach { denied += it }

								SPEAKING_PERMISSIONS
									.filter { allowedPerms?.contains(it) == true }
									.forEach { allowed += it }

								reason = "Mute role permissions update triggered by ${user.asUser().tag}"
							}

							channelsUpdated += 1
						}
					}

					respond {
						content = if (channelsUpdated > 0) {
							"Updated permissions for $channelsUpdated channel/s."
						} else {
							"No channels to update."
						}
					}

					val roleUpdated = if (role.permissions.values.isNotEmpty()) {
						role.edit {
							permissions = Permissions()

							reason = "Mute role permissions update triggered by ${user.asUser().tag}"
						}

						respond { content = "Mute role permissions cleared." }

						true
					} else {
						respond { content = "Mute role already has no extra permissions." }

						false
					}

					if (channelsUpdated > 0 || roleUpdated) {
						guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
							title = "Mute role updated"
							color = DISCORD_BLURPLE

							description =
								"Mute role (${role.mention} / `${role.id}`) permissions updated by " +
										"${user.mention}."

							timestamp = Clock.System.now()

							field {
								name = "Channels Updated"
								inline = true

								value = "$channelsUpdated"
							}

							field {
								name = "Role Updated"
								inline = true

								value = if (roleUpdated) "Yes" else "No"
							}
						}
					}
				}
			}

			ephemeralSlashCommand {
				name = "lock-server"
				description = "Lock the server, preventing anyone but staff from talking"

				allowInDms = false

				guild(guildId)

				check { isAdminOrHasOverride() }

				action {
					val roles = guild!!.getSettings()?.moderatorRoles
						?: listOf(
								when (guild!!.id) {
								LADYSNAKE_GUILD -> LADYSNAKE_MODERATOR_ROLE
								YOUTUBE_GUILD -> YOUTUBE_MODERATOR_ROLE

								else -> throw DiscordRelayedException("Incorrect server ID: ${guild?.id?.value}")
							}
						)

					val moderatorRoles = roles.mapNotNull { guild!!.getRoleOrNull(it) }
					val everyoneRole = guild!!.asGuild().getEveryoneRole()

					everyoneRole.edit {
						permissions = everyoneRole.permissions
							.minus(Permission.AddReactions)
							.minus(Permission.CreatePrivateThreads)
							.minus(Permission.CreatePublicThreads)
							.minus(Permission.SendMessages)
							.minus(Permission.SendMessagesInThreads)

						reason = "Server locked down by ${user.asUser().tag}"
					}

					guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
						title = "Server locked"
						color = DISCORD_RED

						description = "Server was locked by ${user.mention}."
						timestamp = Clock.System.now()
					}

					respond {
						content = "Server locked."
					}
				}
			}

			ephemeralSlashCommand {
				name = "unlock-server"
				description = "Unlock the server, allowing users to talk again"

				allowInDms = false

				guild(guildId)

				check { isAdminOrHasOverride() }

				action {
					val roles = guild!!.getSettings()?.moderatorRoles
						?: listOf(
								when (guild!!.id) {
								LADYSNAKE_GUILD -> LADYSNAKE_MODERATOR_ROLE
								YOUTUBE_GUILD -> YOUTUBE_MODERATOR_ROLE

								else -> throw DiscordRelayedException("Incorrect server ID: ${guild?.id?.value}")
							}
						)

					val everyoneRole = guild!!.getRole(guild!!.id)

					everyoneRole.edit {
						permissions = everyoneRole.permissions
							.plus(Permission.AddReactions)
							.plus(Permission.CreatePrivateThreads)
							.plus(Permission.CreatePublicThreads)
							.plus(Permission.SendMessages)
							.plus(Permission.SendMessagesInThreads)

						reason = "Server unlocked by ${user.asUser().tag}"
					}

					guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
						title = "Server unlocked"
						color = DISCORD_GREEN

						description = "Server was unlocked by ${user.mention}."
						timestamp = Clock.System.now()
					}

					respond {
						content = "Server unlocked."
					}
				}
			}

			ephemeralSlashCommand(::LockArguments) {
				name = "lock"
				description = "Lock a channel, so only moderators can interact in it"

				allowInDms = false

				guild(guildId)

				check { hasBaseModeratorRole() }

				action {
					var channelObj = arguments.channel ?: channel.asChannel()

					if (channelObj.type in THREAD_CHANNEL_TYPES) {
						channelObj = channelObj.asChannelOf<ThreadChannel>().parent.asChannel()
					}

					if (channelObj.type !in TEXT_CHANNEL_TYPES) {  // Should never happen, but we handle it for safety
						respond {
							content = "This command can only be run in a guild text channel."
						}
					}

					val modRoles = guild!!.getSettings()?.moderatorRoles
						?: listOf(
								when (guild!!.id) {
								LADYSNAKE_GUILD -> LADYSNAKE_MODERATOR_ROLE
								YOUTUBE_GUILD -> YOUTUBE_MODERATOR_ROLE

								else -> throw DiscordRelayedException("Incorrect server ID: ${guild?.id?.value}")
							}
						)

					val ch = channelObj.asChannelOf<TextChannel>()

					try {
						modRoles.forEach { staffRoleId ->
							ch.editRolePermission(staffRoleId) {
								SPEAKING_PERMISSIONS.forEach { allowed += it }

								reason = "Channel locked by ${user.asUser().tag}"
							}
						}
					} catch (e: RestRequestException) {
						// it happens
						logger.warn(e) { "Channel lock for <#${ch.id}> failed ensuring moderator roles have speaking permissions" }
					}

					ch.editRolePermission(guild!!.id) {
						SPEAKING_PERMISSIONS.forEach { denied += it }

						reason = "Channel locked by ${user.asUser().tag}"
					}

					ch.createMessage {
						content = "Channel locked by a moderator."
					}

					guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
						title = "Channel locked"
						color = DISCORD_RED

						description = "Channel ${ch.mention} was locked by ${user.mention}."
						timestamp = Clock.System.now()
					}

					respond {
						content = "Channel locked."
					}
				}
			}

			ephemeralSlashCommand(::LockArguments) {
				name = "unlock"
				description = "Unlock a previously locked channel"

				allowInDms = false

				guild(guildId)

				check { hasBaseModeratorRole() }

				action {
					var channelObj = arguments.channel ?: channel.asChannel()

					if (channelObj.type in THREAD_CHANNEL_TYPES) {
						channelObj = channelObj.asChannelOf<ThreadChannel>().parent.asChannel()
					}

					if (channelObj.type !in TEXT_CHANNEL_TYPES) {  // Should never happen, but we handle it for safety
						respond {
							content = "This command can only be run in a guild text channel."
						}
					}

					val ch = channelObj.asChannelOf<TextChannel>()

					ch.getPermissionOverwritesForRole(guild!!.id)
						?.delete("Channel unlocked by ${user.asUser().tag}")

					ch.createMessage {
						content = "Channel unlocked by a moderator."
					}

					guild?.asGuildOrNull()?.getModLogChannel()?.createEmbed {
						title = "Channel unlocked"
						color = DISCORD_GREEN

						description = "Channel ${ch.mention} was unlocked by ${user.mention}."
						timestamp = Clock.System.now()
					}

                    respond {
                        content = "Channel unlocked."
                    }
                }
            }

			ephemeralUserCommand {
				name = "Manage Ping Groups"

				allowInDms = false

				guild(guildId)

				check {
					any(
						{ hasBaseModeratorRole() },
						{ failIf(event.interaction.user.id != event.interaction.targetId) }
					)
				}

				action {
					val checks = CheckContext(event, getLocale())
					checks.hasBaseModeratorRole()
					val allowAny = checks.passed

					val userId = event.interaction.targetId

					val selectable = pingGroups.getAll(guildId, allowAny).toSet()
					val removable = pingGroups.getByUser(guildId, userId).toSet()

					val groups = selectable + removable

					if (groups.isEmpty()) {
						respond {
							content = "**Error:** There are no ping groups that you can add or remove."
						}
						return@action
					}

					respond {
						content = "Please select the ping groups you'd like to be subscribed to. " +
								"If you'd like to unsubscribe from a group, simply unselect it."

						components {
							ephemeralStringSelectMenu {
								placeholder = "Ping Groups"

								minimumChoices = 0
								maximumChoices = null // no limit

								for (group in groups.sortedBy { it.name }) {
									option(group.name, group._id) {
										group.emoji?.takeUnless { it.isBlank() }?.toReaction()?.let(::emoji)
										description = group.desc
										default = group in removable
									}
								}

								action {
									val values = event.interaction.values.toSet()
									val selected = selectable.filter { it._id in values }
									val removed = removable.filter { it._id !in values }

									for (group in selected) {
										group.users.add(userId)
										pingGroups.set(group)
									}

									for (group in removed) {
										group.users.remove(userId)
										pingGroups.set(group)
									}

									respond {
										content = "Ping groups updated."
									}
								}
							}
						}
					}
				}
			}

			ephemeralSlashCommand {
				name = "ping-groups"
				description = "Manage ping groups"

				allowInDms = false

				guild(guildId)

				check { hasBaseModeratorRole() }

				ephemeralSubCommand(::PingGroupCreateModal) {
					name = "create"
					description = "Create a new ping group"

					guild(guildId)

					check { hasBaseModeratorRole() }

					action {
						it!!

						val newGroup = PingGroup(
							guildId = guildId,
							_id = it.groupId.value!!,
							name = it.name.value!!,
							canSelfSubscribe = it.allowAnyone.value.toBoolean(),
							desc = it.desc.value,
							emoji = it.emoji.value
						)

						pingGroups.set(newGroup)

						respond {
							content = "Ping group created."
						}
					}
				}

				ephemeralSubCommand(::PingGroupDeleteArguments) {
					name = "delete"
					description = "Delete a ping group"

					guild(guildId)

					check { hasBaseModeratorRole() }

					action {
						val result = pingGroups.delete(arguments.id)

						if (result.deletedCount != 0L) {
							respond {
								content = "Ping group deleted."
							}
						} else {
							respond {
								content = "**Error:** Ping group not found."
							}
						}
					}
				}

				ephemeralSubCommand(::PingGroupPingArguments) {
					// the real meat and potatoes
					name = "ping"
					description = "Ping a ping group"

					guild(guildId)

					check { hasBaseModeratorRole() }

					action {
						val group = pingGroups.get(arguments.id) ?: run {
							respond {
								content = "**Error:** Ping group not found."
							}
							return@action
						}

						val message = arguments.message ?: "Ping to group '${group.name}' from ${user.mention}"
						val channel = arguments.channel?.asChannelOfOrNull<MessageChannel>() ?: channel.asChannel()

						val fullMessage = buildString {
							append(message)
							append(" (")

							var first = true
							for (user in group.users) {
								if (!first) append(", ")
								first = false

								append("<@")
								append(user)
								append(">")
							}

							append(")")
						}
						channel.createMessage(fullMessage)

						respond {
							content = "Ping sent."
						}
					}
				}
			}
        }

        event<MessageCreateEvent> {
            check { inLadysnakeGuild() }
            check { isNotBot() }
            check { isNotInThread() }
            check { failIfNot(event.message.channelId in THREAD_ONLY_CHANNELS) }

            action {
                val settings = event.getGuildOrNull()!!.getSettings() ?: return@action
                if (event.message.channelId in settings.threadOnlyChannels) {
                    if (event.message.attachments.isEmpty()) {
                        event.message.delete("Found in thread-only channel without attachment")
                        event.member!!.dm {
                            content = "Your message in <#${event.message.channelId}> was deleted. " +
                                    "Please use the appropriate thread to talk about a post in this channel."
                        }
                    } else {
                        val channel = event.message.channel.asChannelOf<TextChannel>()

                        val messageContent = event.message.content

                        @Suppress("MagicNumber")
                        val threadName = if (messageContent.isBlank()) {
                            val attachments = event.message.attachments
                            if (attachments.size == 1) {
                                attachments.first().filename
                            } else {
                                attachments.size.toString() + " attachments"
                            }
                        } else if (messageContent.length > 25) {
                            messageContent.substring(0, 22) + "..."
                        } else {
                            messageContent
                        }

						val archiveDuration = channel.getArchiveDuration(guildFor(event)?.getSettings())
                        val thread = channel.startPublicThreadWithMessage(
                            event.message.id,
                            threadName
						) {
							autoArchiveDuration = archiveDuration
							reason = "Automatic thread for thread-only channel"
						}

                        val ownedThread = OwnedThread(
                            thread.id,
                            event.member!!.id,
                            event.guildId!!,
                            preventArchiving = false
                        )

                        threads.set(ownedThread)

                        logger.info { "Thread auto-created for ${event.message.author!!.tag}" }

                        val role = when (event.message.getGuild().id) {
                            LADYSNAKE_GUILD -> event.message.getGuild().getRole(LADYSNAKE_MODERATOR_ROLE)
                            YOUTUBE_GUILD -> event.message.getGuild().getRole(YOUTUBE_MODERATOR_ROLE)
                            else -> return@action
                        }

                        thread.addUser(event.message.author!!.id)

                        val message = thread.createMessage {
                            content = "Oh hey there, we just gotta do a bit of moderator-related setup " +
                                    "for this fun new creation..."
                        }

                        thread.withTyping {
                            delay(MESSAGE_EDIT_DELAY)
                        }

                        message.edit {
                            content = "Hey, ${role.mention}! Say cheese!"
                        }

                        thread.withTyping {
                            delay(MESSAGE_EDIT_DELAY)
                        }

                        message.edit {
                            content = "Welcome to your thread, ${event.message.author!!.mention}! " +
                                    "You can discuss your post with others here, and you can use the thread " +
                                    "commands, both `/thread` and the message commands, to manage your thread. "
                        }

                        message.pin("First message in the thread.")
                    }
                }
            }
        }

		chatCommand(::SelfTimeoutArguments) {
			name = "self-timeout"
			description = "Time yourself out for up to three days"

			check { inLadysnakeGuild() }

			action {
				lateinit var components: ComponentContainer

				val relative = Clock.System.now()
					.plus(arguments.duration, TimeZone.UTC)
					.toDiscord(TimestampType.RelativeTime)

				val absolute = Clock.System.now()
					.plus(arguments.duration, TimeZone.UTC)
					.toDiscord(TimestampType.LongDateTime)

				message.respond {
					content = "You've requested a timeout, which will end $relative (at $absolute).\n\n" +

						"This timeout will be applied as soon as you click the button below. However, please " +
						"note that **we will not be removing timeouts you set on yourself** in most " +
						"situations, even if you request it. You should avoid setting timeouts you're not " +
						"sure about.\n\n" +

						"Are you sure you'd like to apply this timeout?"

					components = components {
						ephemeralButton {
							label = "Confirm"
							style = ButtonStyle.Danger

							@OptIn(DoNotChain::class)
							action {
								member!!.asMember()
									.timeout(
										arguments.duration,
										reason = "Requested using /self-timeout"
									)

								guild?.asGuild()?.getCozyLogChannel()?.createEmbed {
									title = "Requested timeout automatically applied"
									color = DISCORD_BLURPLE

									userField(user.asUser())

									field {
										name = "Duration"
										value = arguments.duration.format(SupportedLocales.ENGLISH)
									}

									field {
										name = "Relative ending time"
										value = relative
									}

									field {
										name = "Absolute ending time"
										value = absolute
									}
								}

								respond {
									content = "Your timeout has been applied. See you $relative!"
								}

								components.cancel()
							}
						}

						ephemeralButton {
							label = "Cancel"
							style = ButtonStyle.Secondary

							action {
								respond {
									content = "Your timeout has been cancelled."
								}

								components.cancel()
							}
						}
					}
				}
			}
		}

        ephemeralSlashCommand {
            name = "guilds"
            description = "Manage guilds that the bot is in"

            check {
                any(
                    { hasPermissionInMainGuild(Permission.Administrator) },
                    { failIfNot { event.interaction.user.id in OVERRIDING_USERS } }
                )
            }

            ephemeralSubCommand {
                name = "list"
                description = "List all guilds that the bot is in"

                check {
                    any(
                        { hasPermissionInMainGuild(Permission.Administrator) },
                        { failIfNot { event.interaction.user.id in OVERRIDING_USERS } }
                    )
                }

                action {
                    val guilds = this@UtilityExtension.kord.guilds
                        .map { with(it) { "$name ($id)" } }
                        .toList()
                        .joinToString("\n") { "**»** $it" }

                    respond {
                        embed {
                            title = "Guilds"
                            description = guilds
                        }
                    }
                }
            }

            ephemeralSubCommand(::LeaveGuildArguments) {
                name = "leave"
                description = "Leave a guild"

                check {
                    any(
                        { hasPermissionInMainGuild(Permission.Administrator) },
                        { failIfNot { event.interaction.user.id in OVERRIDING_USERS } }
                    )
                }

                action {
                    val guild = arguments.guild

                    guild.leave()

                    if (channel.id !in guild.channelIds) {
                        respond {
                            embed {
                                title = "Left guild"
                                description = "Left guild **${guild.name}** (${guild.id})"
                            }
                        }
                    } else {
                        // we just left the guild, so we can't send a message
                        user.asUser().dm {
                            embed {
                                title = "Left guild"
                                description = "Left guild **${guild.name}** (${guild.id})"
                            }
                        }
                    }
                }
            }
        }

		ephemeralUserCommand {
			name = "Force verify"

			check { hasBaseModeratorRole() }
			check { inLadysnakeGuild() }

			action {
				val message = "Temp role for force verify"
				val tempRole = guild!!.createRole {
					name = "Temp verification role"
					reason = message
				}

				val member = event.interaction.target.asMember(guild!!.id)

				member.addRole(tempRole.id, message)

				respond {
					content = "Force verified ${user.mention}"
				}
			}
		}

		scheduler.schedule(5.minutes, repeat = true) {
			val tenMinutesAgo = Clock.System.now() - 10.minutes
			kord.guilds.collect { guild ->
				guild.roles.filter { it.name == "Temp verification role" }
					.filter { it.id.timestamp < tenMinutesAgo }
					.collect {
						it.delete("Temp role for force verify (>=10 minutes old)")
					}
			}

			threads.getAllWithDuration().consumeEach {
				val openTime = it._id.timestamp
				val channel = kord.getChannelOf<ThreadChannel>(it._id)!!
				val archiveTime = channel.archiveTimestamp.takeIf { channel.isArchived }
				val guildSettings = getKoin().get<ServerSettingsCollection>().get(channel.guildId)
				val now = Clock.System.now()

				val maxThreadDuration = it.maxThreadDuration ?: guildSettings?.defaultTotalMaxThreadLength
				if (maxThreadDuration != null && now > openTime + maxThreadDuration) {
					channel.edit {
						archived = true
						locked = true
						reason = "Thread automatically archived and locked after reaching max duration"
					}

					// Prevent automatic re-archiving if a moderator has manually unarchived the thread
					it.maxThreadDuration = INFINITE
					threads.set(it)
				}

				val maxThreadAfterIdle = it.maxThreadAfterIdle ?: guildSettings?.defaultIdleMaxThreadLength
				if (maxThreadAfterIdle != null && archiveTime != null && now > archiveTime + maxThreadAfterIdle) {
					channel.edit {
						archived = true
						locked = true
						reason = "Thread automatically locked after reaching max idle duration"
					}
				}
			}
		}
	}

	inner class SelfTimeoutArguments : Arguments() {
		val duration by duration {
			name = "duration"
			description = "How long to time yourself out for; no more than three days"

			positiveOnly = true

			validate {
				if (value > DateTimePeriod(days = 3)) {
					fail("You may not time yourself out for more than three days.")
				}
			}
		}
	}

	inner class PinMessageArguments : Arguments() {
		val message by message {
			name = "message"
			description = "Message link or ID to pin/unpin"
		}
	}

	inner class RenameArguments : Arguments() {
		val name by string {
			name = "name"
			description = "Name to give the current thread"
		}
	}

	inner class ArchiveArguments : Arguments() {
		val lock by defaultingBoolean {
			name = "lock"
			description = "Whether to lock the thread, if you're staff - defaults to false"

			defaultValue = false
		}
	}

	inner class AutoLockArguments : Arguments() {
		val maxTotalTime by optionalDuration {
			name = "max-total-time"
			description = "Maximum total time to keep the thread unlocked from creation."
		}

		val maxIdleTime by optionalDuration {
			name = "max-idle-time"
			description = "Maximum time to keep the thread unlocked after archival."
		}
	}

	inner class SetOwnerArguments : Arguments() {
		val user by user {
			name = "user"
			description = "User to set as the owner of the thread"
		}
	}

	inner class LockArguments : Arguments() {
		val channel by optionalChannel {
			name = "channel"
			description = "Channel to lock/unlock, if not the current one"
		}
	}

	inner class MuteRoleArguments : Arguments() {
		val role by optionalRole {
			name = "role"
			description = "Mute role ID, if the role isn't named Muted"
		}
	}

	inner class SayArguments : Arguments() {
		val message by string {
			name = "message"
			description = "Message to send"
		}

		val target by optionalChannel {
			name = "target"
			description = "Channel to use, if not this one"

			validate {
				failIf("${value?.mention} is not a guild text channel.") {
                    value != null && value!!.type !in listOf(
                        ChannelType.GuildText,
                        ChannelType.GuildNews,
                        ChannelType.PublicNewsThread,
                        ChannelType.PublicGuildThread
                    )
                }
            }
        }

        val flags by optionalString {
			name = "flags"
            description = "A comma-separated list of flags to use"

            mutate {
                it?.split(",")?.joinToString(",") { s -> s.trim() }
            }
        }
	}

	inner class LeaveGuildArguments : Arguments() {
        val guild by guild {
            name = "guild"
            description = "Guild to use. To specify this guild, use its ID."
		}
	}

	inner class PingGroupPingArguments : Arguments() {
		val id by string {
			name = "id"
			description = "ID of the ping group to ping"

			autoComplete { event ->
				val groups = pingGroups.getAll(event.interaction.getChannel().asChannelOf<GuildMessageChannel>().guildId, true)
				suggestStringMap(groups.associate { it.name to it._id })
			}
		}

		val message by optionalString {
			name = "message"
			description = "Message to send to the ping group"

			mutate {
				it?.trim()
			}
		}

		val channel by optionalChannel {
			name = "channel"
			description = "Channel to send the message in, if not the current one"
		}
	}

	inner class PingGroupDeleteArguments : Arguments() {
		val id by string {
			name = "id"
			description = "ID of the ping group to delete"

			autoComplete { event ->
				val groups = pingGroups.getAll(event.interaction.getChannel().asChannelOf<GuildMessageChannel>().guildId, true)
				suggestStringMap(groups.associate { it.name to it._id })
			}
		}
	}

	inner class PingGroupCreateModal : ModalForm() {
		override var title = "Create Ping Group"

		val groupId = lineText {
			label = "ID used to refer to the ping group"
			placeholder = "cool-group"
			maxLength = 100
			required = true
		}

		val name = lineText {
			label = "Name of the ping group"
			placeholder = "Cool Group"
			maxLength = 100
			required = true
		}

		val desc = lineText {
			label = "Description of the ping group"
			placeholder = "A cool group for cool people"
			maxLength = 100
			required = false
		}

		val emoji = lineText {
			label = "Emoji to use for the ping group"
			placeholder = "👍"
			maxLength = 100
			required = false
		}

		val allowAnyone = lineText {
			label = "Allow anyone to subscribe? (true/false)"
			placeholder = "false by default"
			maxLength = 5
			required = false
		}
	}
}
