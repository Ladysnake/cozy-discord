/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.logs.parsers

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.quiltmc.community.modes.quilt.extensions.logs.misc.ModrinthVersion
import kotlin.time.Duration.Companion.minutes

private val MATCH_REGEX = "- quilted_fabric_api ([^\\n]+)".toRegex(RegexOption.IGNORE_CASE)
private const val MODRINTH_URL = "https://api.modrinth.com/v2/project/qsl/version"

private val CACHE_TIMEOUT = 10.minutes

class QSLVersionParser : BaseLogParser {
    private var lastCheck: Instant? = null
    private var latestVersion: ModrinthVersion? = null

    val client = HttpClient {
        install(ContentNegotiation) {
            json(
                Json { ignoreUnknownKeys = true },
                ContentType.Any
            )
        }

        expectSuccess = true
    }

    override suspend fun getMessages(logContent: String): List<String> {
        val messages: MutableList<String> = mutableListOf()
        val match = MATCH_REGEX.find(logContent)

        if (match != null) {
            val providedVersion = match.groups[1]!!.value.trim()
            val (version, url) = getVersion() ?: return emptyList()

            if (!providedVersion.equals(version, true)) {
                messages.add(
                    "You appear to be using version `$providedVersion` of QSL - please try updating to " +
                            "[version `$version`]($url)."
                )
            }
        }

        return messages
    }

    suspend fun getVersion(): Pair<String, String>? {
        val now = Clock.System.now()

        if (latestVersion == null || lastCheck == null || now - lastCheck!! > CACHE_TIMEOUT) {
            val response: List<ModrinthVersion> = client.get(MODRINTH_URL).body()

            val latest = response
                .maxByOrNull { it.datePublished }
                ?: return null

            latestVersion = latest
        }

        return latestVersion!!.versionNumber to latestVersion!!.files.first { it.primary }.url
    }
}
