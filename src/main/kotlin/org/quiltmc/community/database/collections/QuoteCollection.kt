/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.database.collections

import kotlinx.coroutines.flow.filter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.litote.kmongo.eq
import org.quiltmc.community.database.Collection
import org.quiltmc.community.database.Database
import org.quiltmc.community.database.entities.Quote

class QuoteCollection : KoinComponent {
    private val db: Database by inject()
    private val col = db.mongo.getCollection<Quote>(name)

    suspend fun get(id: Int) = col.findOne(Quote::_id eq id)

    suspend fun searchByContent(content: String) = col.find().toFlow().filter { content in it.quote }

    suspend fun getAll() = col.find().toFlow()

    suspend fun add(quote: Quote) = col.save(quote)

    suspend fun new(text: String, author: String): Int {
        val allQuotes = col.find()
        val max = allQuotes.descendingSort(Quote::_id).first()?._id ?: 0

        val newQuote = Quote(max + 1, text, author)
        col.save(newQuote)
        return newQuote._id
    }

    suspend fun delete(id: Int) = col.deleteOne(Quote::_id eq id)

    companion object : Collection("quotes")
}
