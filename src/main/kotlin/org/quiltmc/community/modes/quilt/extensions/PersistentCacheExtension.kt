/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(KorioExperimentalApi::class)

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.soywiz.korio.async.useIt
import com.soywiz.korio.compression.lzma.Lzma
import com.soywiz.korio.compression.uncompressStream
import com.soywiz.korio.compression.util.BitReader
import com.soywiz.korio.experimental.KorioExperimentalApi
import com.soywiz.korio.file.VfsOpenMode
import com.soywiz.korio.file.std.localCurrentDirVfs
import com.soywiz.korio.stream.readAll
import com.soywiz.korio.stream.toAsync
import com.soywiz.korio.stream.toAsyncStream
import dev.kord.cache.api.putAll
import dev.kord.cache.api.query
import dev.kord.core.cache.data.MessageData
import dev.kord.core.event.gateway.ConnectEvent
import dev.kord.core.event.gateway.DisconnectEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.*

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

    override suspend fun setup() {
        event<DisconnectEvent> {
            action {
                extension.bot.withLock {
                    val cache = event.kord.cache

                    val messageData = cache.query<MessageData>()
                    val messageSerializer = MessageData.serializer()
                    val messagesListJsonified = mutableListOf<JsonObject>()

                    messageData.asFlow().collectLatest {
                        val serialized = Json.encodeToJsonElement(messageSerializer, it)
                        messagesListJsonified.add(serialized.jsonObject)
                    }

                    val jsonArray = JsonArray(messagesListJsonified)

                    saveJsonToFile(jsonArray, "cache/messages.json.gz")
                }
            }
        }

        event<ConnectEvent> {
            action {
                extension.bot.withLock {
                    val cache = event.kord.cache

                    val messageSerializer = MessageData.serializer()
                    val messagesList = mutableListOf<MessageData>()

                    val jsonArray = loadJsonFromFile("cache/messages.json.gz")

                    jsonArray.jsonArray.forEach {
                        val deserialized = Json.decodeFromJsonElement(messageSerializer, it)
                        messagesList.add(deserialized)
                    }

                    cache.putAll(messagesList)
                }
            }
        }
    }

    private suspend fun saveJsonToFile(json: JsonElement, path: String) {
        val vfsFile = localCurrentDirVfs[path]

        json.toString().byteInputStream(Charsets.UTF_8).toAsync().toAsyncStream().useIt { input ->
            vfsFile.open(VfsOpenMode.CREATE_OR_TRUNCATE).useIt { output ->
                Lzma.compress(BitReader(input), output)
            }
        }
    }

    private suspend fun loadJsonFromFile(path: String): JsonElement {
        val vfsFile = localCurrentDirVfs[path]

        val jsonString = vfsFile.open(VfsOpenMode.READ).useIt { input ->
            Lzma.uncompressStream(input).useIt { output ->
                output.readAll().toString(Charsets.UTF_8)
            }
        }

        return JSON.parseToJsonElement(jsonString)
    }
}
