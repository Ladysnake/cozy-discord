/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(KorioExperimentalApi::class)

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.events.EventContext
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.sentry.BreadcrumbType
import com.soywiz.klock.seconds
import com.soywiz.korio.async.delay
import com.soywiz.korio.async.useIt
import com.soywiz.korio.compression.lzma.Lzma
import com.soywiz.korio.compression.uncompressStream
import com.soywiz.korio.compression.util.BitReader
import com.soywiz.korio.experimental.KorioExperimentalApi
import com.soywiz.korio.file.VfsOpenMode
import com.soywiz.korio.file.std.localCurrentDirVfs
import com.soywiz.korio.lang.FileNotFoundException
import com.soywiz.korio.stream.readAll
import com.soywiz.korio.stream.toAsync
import com.soywiz.korio.stream.toAsyncStream
import dev.kord.cache.api.putAll
import dev.kord.cache.api.query
import dev.kord.core.cache.data.MessageData
import dev.kord.core.event.gateway.ConnectEvent
import dev.kord.core.event.gateway.DisconnectEvent
import io.sentry.Sentry.setExtra
import io.sentry.SentryLevel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.*

private const val MAX_RETRY_COUNT = 10

val JSON = Json {
	encodeDefaults = false
	ignoreUnknownKeys = true
	prettyPrint = false
	isLenient = true
}

/**
 * An extension to allow caching information
 * across bot restarts. Currently, only messages
 * are saved to a cache file.
 */
class PersistentCacheExtension : Extension() {
	override val name = "persistent-cache"
	private val cacheFile = localCurrentDirVfs["cache.json.lzma"]
	private val lock = localCurrentDirVfs["cache.lock"]

	override suspend fun setup() {
        event<DisconnectEvent> {
            action {
                extension.bot.withLock {
                    sentry.breadcrumb(BreadcrumbType.Info) {
						message = "Disconnected - caching messages"
					}
                    val cache = event.kord.cache

                    val messageData = cache.query<MessageData>()
                    val messageSerializer = MessageData.serializer()
                    val messagesListJsonified = mutableListOf<JsonObject>()

                    messageData.asFlow().collectLatest {
                        val serialized = Json.encodeToJsonElement(messageSerializer, it)
                        messagesListJsonified.add(serialized.jsonObject)
                    }

                    val jsonArray = JsonArray(messagesListJsonified)

                    saveJsonToFile(jsonArray)
                    sentry.breadcrumb(BreadcrumbType.Info) {
                        message = "Finished caching messages"
                        data["count"] = messagesListJsonified.size
                    }
                }
            }
        }

        event<ConnectEvent> {
            action {
                extension.bot.withLock {
                    sentry.breadcrumb(BreadcrumbType.Info) {
						message = "Connected - loading messages"
					}
                    try {
                        val cache = event.kord.cache

                        val messageSerializer = MessageData.serializer()
                        val messagesList = mutableListOf<MessageData>()

                        val jsonArray = loadJsonFromFile()

                        jsonArray.jsonArray.forEach {
                            val deserialized = Json.decodeFromJsonElement(messageSerializer, it)
                            messagesList.add(deserialized)
                        }

                        cache.putAll(messagesList)

                        sentry.breadcrumb(BreadcrumbType.Info) {
                            message = "Finished loading messages"
                            data["count"] = messagesList.size
                        }
                    } catch (e: FileNotFoundException) {
                        sentry.breadcrumb(BreadcrumbType.Info) {
                            message = "No messages to load"
                            data["exception"] = e.message ?: "<null>"
                        }
                    }
                }
            }
        }
	}

	private suspend fun EventContext<*>.saveJsonToFile(json: JsonElement) {
        cacheFile.parent.mkdirs()

		var tries = 0
		while (lock.exists() && tries++ < MAX_RETRY_COUNT) {
			delay(1.seconds)
		}

		if (lock.exists()) {
			sentry.captureMessage("Failed to acquire lock for cache file") {
				level = SentryLevel.WARNING
				setExtra("action", "saveJsonToFile")
			}
			return
		}

		lock.openUse(VfsOpenMode.CREATE_NEW) {
			write(0)
		}

        val bytes = json.toString().encodeToByteArray()

        bytes.inputStream().toAsync(bytes.size.toLong()).toAsyncStream().useIt { input ->
            cacheFile.open(VfsOpenMode.CREATE_OR_TRUNCATE).useIt { output ->
                Lzma.compress(BitReader.forInput(input), output)
            }
        }

		lock.delete()
	}

	private suspend fun EventContext<*>.loadJsonFromFile(): JsonElement {
		if (!cacheFile.exists()) {
			throw FileNotFoundException("Cache file does not exist")
		}

		var tries = 0
		while (lock.exists() && tries++ < MAX_RETRY_COUNT) {
			delay(1.seconds)
		}

		if (lock.exists()) {
			sentry.captureMessage("Failed to acquire lock for cache file") {
				level = SentryLevel.WARNING
				setExtra("action", "loadJsonFromFile")
			}
			return JsonArray(emptyList())
		}

		lock.openUse(VfsOpenMode.CREATE_NEW) {
			write(0)
		}

        val jsonString = cacheFile.open(VfsOpenMode.READ).useIt { input ->
            Lzma.uncompressStream(input).useIt { output ->
                output.readAll().toString(Charsets.UTF_8)
            }
        }

		lock.delete()

        return JSON.parseToJsonElement(jsonString)
	}
}
