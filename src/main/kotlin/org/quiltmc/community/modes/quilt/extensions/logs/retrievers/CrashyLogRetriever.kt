/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.logs.retrievers

import dev.kord.core.entity.Message
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import mu.KotlinLogging
import org.intellij.lang.annotations.Language
import org.jsoup.Jsoup

@Language("regexp")
private const val CRASHY_REGEX = """crashy\.net/[A-Za-z\d]+"""

private const val CRASHY_URL_TEMPLATE = "https://%s/getCrash/$1"
private const val CRASHY_FALLBACK = "https://europe-west1-crashy-9dd87.cloudfunctions.net/getCrash/$1"

/*
 * To whoever is reading this:
 *
 * I'm sorry for the mess, but Crashy does a terrible job of giving parsable logs.
 * To help understand the mess, I have many comments sprinkled throughout the code
 * to hopefully indicate why I do what I do.
 *
 * First: the two variables at the top of the file.
 * - CRASHY_REGEX is the regex used to find Crashy links. It's simple (hopefully)
 * - CRASHY_URL_TEMPLATE is *a template for a template* for the URL to retrieve the log from.
 *   It has two placeholders: the first is the portion of the URL that is constant between each
 *   log, and the second is the "slug" of the log.
 *   The template that is constructed is found in `initialize`. Keep reading on to find out.
 * - CRASHY_FALLBACK is the URL to use if the initialization goes wrong. It'll hopefully
 *   be a solution until Crashy changes where they store their logs.
 */
class CrashyLogRetriever : BaseLogRetriever {
	private val crashyRegex = CRASHY_REGEX.toRegex()
	private val httpClient = HttpClient {}
	private val logger = KotlinLogging.logger {}
	private var hasInitialized = false // Don't do unnecessary work
	private lateinit var crashyUrl: String // The real template

	private suspend fun initialize(partialUrl: String) {
        // First, find a `<script url="...">` tag and load its data
        val url = "https://$partialUrl?raw"
        val soup = Jsoup.connect(url).get()
        val runningScript = soup.select("script").first { "main" in it.attr("src") }

        val scriptUrl = runningScript.attr("abs:src")
        val script = httpClient.get(scriptUrl).bodyAsText()

        // Search for a couple particular concatenation chains
        // I can't explain what they do, put them into regexr.com or something to understand the regexes better
        val searchString1 = """\.concat\((.),\w?"/getCrash/"\)\.concat\(.\)""".toRegex()
        val searchString2 = """://"\)\w?\.concat\("(.+?)"\);""".toRegex()

        // Find the first match .concat(X, "/getCrash/").concat(Y) (we only care about X)
        val search1 = searchString1.find(script) ?: return
        val endSearch = search1.range.first
        val startSearch = script.substring(0..endSearch).lastIndexOf(search1.groupValues[1] + '=')

        // Find the second match .concat("companyname.website") (we care about the URL)
        val newSearchRange = script.substring(startSearch..endSearch)
        val search2 = searchString2.find(newSearchRange) ?: return

        // We found it! Let's format and chuck it in `crashyUrl`
        crashyUrl = CRASHY_URL_TEMPLATE.format(search2.groupValues[1])

        hasInitialized = true // initialization successful
	}

	override suspend fun getLogContent(message: Message): List<String> {
        val results = mutableListOf<String>()

        // Find all Crashy links in the message
        val content = message.content
        val matches = crashyRegex.findAll(content)

        if (matches.any()) { // There's at least one match
            if (!hasInitialized) { // Initialize as needed
                initialize(matches.first().value) // We need something to search in
                if (!hasInitialized) { // something went wrong, use the fallback
                    logger.warn { "Couldn't initialize CrashyLogRetriever fully, using fallback URL" }
                    crashyUrl = CRASHY_FALLBACK
                }
            }

            matches.forEach { match ->
                // Get the real URL with "magic"
                val url = crashyUrl.replace("$1", match.value)

                // Get the log and add it to the results
                val log = httpClient.get(url).bodyAsText()

                results.add(log)
            }
        }

        return results
	}
}
