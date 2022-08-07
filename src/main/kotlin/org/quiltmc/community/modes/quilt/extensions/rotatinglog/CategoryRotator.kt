/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.rotatinglog

import dev.kord.core.behavior.channel.asChannelOf
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.channel.edit
import dev.kord.core.behavior.createTextChannel
import dev.kord.core.behavior.edit
import dev.kord.core.entity.channel.Category
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.rest.builder.message.create.UserMessageCreateBuilder
import dev.kord.rest.builder.message.modify.embed
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.quiltmc.community.COLOUR_NEGATIVE
import org.quiltmc.community.COLOUR_POSITIVE
import org.quiltmc.community.copyFrom
import org.quiltmc.community.italic
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoField
import java.util.*
import kotlin.math.abs

// 5 weeks, times 2 for two logging channels
private const val WEEK_DIFFERENCE = 10L

private const val CHECK_DELAY = 1000L * 60L * 30L  // 30 minutes

private val MESSAGE_LOG_NAME_REGEX = Regex("message-log-(\\d{4})-(\\d{2})")
private val OTHER_LOG_NAME_REGEX = Regex("other-log-(\\d{4})-(\\d{2})")

class CategoryRotator(private val category: Category, private val modLog: GuildMessageChannel) {
	private val guild get() = category.guild
	private val messageLogChannel get() = channels.last { it.name.startsWith("message-log-") }
	private val extraLogChannel get() = channels.last { it.name.startsWith("other-log-") }

	var channels: List<GuildMessageChannel> = listOf()
	private var checkJob: Job? = null

	private val logger = KotlinLogging.logger { }
	private val rotationLock = Mutex()

	fun start() {
		checkJob = category.kord.launch {
			populate()
			loop()
		}
	}

	fun stop() {
		checkJob?.cancel()
		checkJob = null
	}

	suspend fun loop() {
		while (true) {
			delay(CHECK_DELAY)

			logger.debug { "Running scheduled channel population." }
			populate()
		}
	}

	suspend fun logMessage(messageBuilder: suspend UserMessageCreateBuilder.() -> Unit) = rotationLock.withLock {
		val message = messageLogChannel.createMessage {
            messageBuilder()
        }

        message.edit {
            if (message.embeds.isNotEmpty()) {
                embed {
                    copyFrom(message.embeds.first())

                    field {
                        name = "Log ID"
                        value = "This field is used for adding notes to this log message.".italic()
                        value += "\n" + message.id.toString()
                    }
                }
            }
        }
	}

	suspend fun logOther(messageBuilder: suspend UserMessageCreateBuilder.() -> Unit) = rotationLock.withLock {
        val message = extraLogChannel.createMessage {
            messageBuilder()
        }

        message.edit {
            if (message.embeds.isNotEmpty()) {
                embed {
                    copyFrom(message.embeds.first())

                    field {
                        name = "Log ID"
                        value = "This field is used for adding notes to this log message.".italic()
                        value += "\n" + message.id.toString()
                    }
                }
            }
        }
	}

	suspend fun populate() {
		rotationLock.withLock {
			@Suppress("TooGenericExceptionCaught")  // Anything could happen, really
			try {
				val now = OffsetDateTime.now(ZoneOffset.UTC)
				val thisWeek = now.getLong(ChronoField.ALIGNED_WEEK_OF_YEAR)
				val thisYear = now.getLong(ChronoField.YEAR)

				var currentChannelExists = false
				var otherChannelExists = false
                val allChannels = mutableListOf<TopGuildMessageChannel>()

				category.channels.toList().forEach {
					if (it is TopGuildMessageChannel) {
						logger.debug { "Checking existing channel: ${it.name}" }

						val match = MESSAGE_LOG_NAME_REGEX.matchEntire(it.name)
                        val otherMatch = OTHER_LOG_NAME_REGEX.matchEntire(it.name)

						if (match != null) {
							val year = match.groups[1]!!.value.toLong()
							val week = match.groups[2]!!.value.toLong()
							val yearWeeks = getTotalWeeks(year.toInt())

							val weekDifference = abs(thisWeek - week)
							val yearDifference = abs(thisYear - year)

							if (year == thisYear && week == thisWeek) {
								logger.debug { "Passing: This is the latest channel." }

								currentChannelExists = true
								allChannels.add(it)
							} else if (year > thisYear) {
								// It's in the future, so this isn't valid!
								logger.debug { "Deleting: This is next year's channel." }

								it.delete()
								logDeletion(it)
							} else if (year == thisYear && week > thisWeek) {
								// It's in the future, so this isn't valid!
								logger.debug { "Deleting: This is a future week's channel." }

								it.delete()
								logDeletion(it)
							} else if (
								yearDifference > 1L || yearDifference != 1L && weekDifference > WEEK_DIFFERENCE
							) {
								// This one is _definitely_ too old.
								logger.debug { "Deleting: This is an old channel." }

								it.delete()
								logDeletion(it)
							} else if (yearDifference == 1L && yearWeeks - week + thisWeek > WEEK_DIFFERENCE) {
								// This is from last year, but more than 5 weeks ago.
								logger.debug { "Deleting: This is an old channel from last year." }

                                it.delete()
                                logDeletion(it)
                            } else {
                                allChannels.add(it)
                            }
                        }

                        if (otherMatch != null) {
                            val year = otherMatch.groups[1]!!.value.toLong()
                            val week = otherMatch.groups[2]!!.value.toLong()
                            val yearWeeks = getTotalWeeks(year.toInt())

                            val weekDifference = abs(thisWeek - week)
                            val yearDifference = abs(thisYear - year)

                            if (year == thisYear && week == thisWeek) {
                                logger.debug { "Passing: This is the latest other channel." }

                                otherChannelExists = true
                                allChannels.add(it)
                            } else if (year > thisYear) {
                                // It's in the future, so this isn't valid!
                                logger.debug { "Deleting: This is next year's other channel." }

                                it.delete()
                                logDeletion(it)
                            } else if (year == thisYear && week > thisWeek) {
                                // It's in the future, so this isn't valid!
                                logger.debug { "Deleting: This is a future week's other channel." }

                                it.delete()
                                logDeletion(it)
                            } else if (
                                yearDifference > 1L || yearDifference != 1L && weekDifference > WEEK_DIFFERENCE
                            ) {
                                // This one is _definitely_ too old.
                                logger.debug { "Deleting: This is an old other channel." }

                                it.delete()
                                logDeletion(it)
                            } else if (yearDifference == 1L && yearWeeks - week + thisWeek > WEEK_DIFFERENCE) {
                                // This is from last year, but more than 5 weeks ago.
                                logger.debug { "Deleting: This is an old other channel from last year." }

                                it.delete()
                                logDeletion(it)
                            } else {
                                allChannels.add(it)
                            }
                        }
                    }
                }

				@Suppress("MagicNumber")
				if (!currentChannelExists) {
					logger.debug { "Creating this week's channel." }

					val yearPadded = thisYear.toString().padStart(4, '0')
					val weekPadded = thisWeek.toString().padStart(2, '0')

					val c = guild.asGuild().createTextChannel("message-log-$yearPadded-$weekPadded") {
						parentId = category.id
					}

					currentChannelExists = true

					logCreation(c)
					allChannels.add(c)
				}

				@Suppress("MagicNumber")
                if (!otherChannelExists) {
                    logger.debug { "Creating this week's other channel" }

                    val yearPadded = thisYear.toString().padStart(4, '0')
                    val weekPadded = thisWeek.toString().padStart(2, '0')

                    val c = guild.asGuild().createTextChannel("other-log-$yearPadded-$weekPadded") {
                        parentId = category.id
                    }

                    otherChannelExists = true

                    logCreation(c)
                    allChannels.add(c)
                }

                @Suppress("MagicNumber", "TooGenericExceptionCaught")
                allChannels.sortBy {
                    @Suppress("DestructuringDeclarationWithTooManyEntries") // detekt added more checks!
                    try {
                        val (logTypes, _, year, week) = it.name.split("-")
                        year.toLong() * 1000 + week.toLong() * 10 + if (logTypes == "message") 0 else 1
                    } catch (e: Exception) {
                        // if it doesn't match the format, it's probably a channel we don't care about.
                        // in that case, return a low number to put it at the bottom.
                        // no need to worry about the order of these, because the sort is said to be stable.
                        -1
                    }
                }

				while (allChannels.size > WEEK_DIFFERENCE) {
					val c = allChannels.removeFirst()

					logger.debug { "Deleting extra channel: ${c.name}" }

					c.delete()
					logDeletion(c)
				}

				channels = allChannels

				logger.debug { "Sorting channels." }

				allChannels.forEachIndexed { i, c ->
					val curPos = c.rawPosition

					if (curPos != i) {
						logger.debug { "Updating channel position for ${c.name}: $curPos -> $i" }

						(allChannels[i].asChannelOf<TextChannel>()).edit {
							position = i
                            reason = "Updating channel position."
						}
					}
				}

				logger.debug { "Done." }
			} catch (t: Throwable) {
				logger.error(t) { "Error thrown during rotation." }
			}
		}
	}

	@Suppress("MagicNumber")  // It's the days in december, c'mon
	private fun getTotalWeeks(year: Int): Int {
		val cal = Calendar.getInstance()

		cal.set(Calendar.YEAR, year)
		cal.set(Calendar.MONTH, Calendar.DECEMBER)
		cal.set(Calendar.DAY_OF_MONTH, 31)

		return cal.getActualMaximum(Calendar.WEEK_OF_YEAR)
	}

	private suspend fun logCreation(channel: GuildMessageChannel) = modLog.createEmbed {
		title = "Message log rotation"
		color = COLOUR_POSITIVE

		description = "Channel created: **#${channel.name} (`${channel.id}`)**"
	}

	private suspend fun logDeletion(channel: GuildMessageChannel) = modLog.createEmbed {
		title = "Message log rotation"
		color = COLOUR_NEGATIVE

		description = "Channel removed: **#${channel.name} (`${channel.id}`)**"
	}
}
