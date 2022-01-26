/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(KorioExperimentalApi::class)

package org.quiltmc.community.modes.quilt.extensions

import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.soywiz.korio.compression.deflate.GZIP
import com.soywiz.korio.compression.util.BitReader
import com.soywiz.korio.experimental.KorioExperimentalApi
import com.soywiz.korio.file.std.localCurrentDirVfs
import com.soywiz.korio.stream.AsyncStreamBase
import com.soywiz.korio.stream.toAsync
import com.soywiz.korio.stream.toAsyncStream
import dev.kord.cache.api.putAll
import dev.kord.cache.api.query
import dev.kord.core.cache.data.MessageData
import dev.kord.core.event.gateway.ConnectEvent
import dev.kord.core.event.gateway.DisconnectEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.shedaniel.linkie.utils.readBytes

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
        val stream = AsyncStreamBase().toAsyncStream()
        val bytes = JSON.encodeToString(json).toByteArray()
        stream.write(bytes)

        val vfsFile = localCurrentDirVfs[path]

        val gzipped = AsyncStreamBase().toAsyncStream()
        GZIP.compress(BitReader(stream), gzipped)

        vfsFile.writeStream(gzipped)

        stream.close()
        gzipped.close()
    }

    private suspend fun loadJsonFromFile(path: String): JsonElement {
        val vfsFile = localCurrentDirVfs[path]
        val stream = vfsFile.readAsSyncStream().toAsync()
        val unzipped = AsyncStreamBase().toAsyncStream()
        GZIP.uncompress(BitReader(stream), unzipped)

        val json = JSON.parseToJsonElement(unzipped.readBytes().toString(Charsets.UTF_8))

        stream.close()
        unzipped.close()

        return json
    }
}
