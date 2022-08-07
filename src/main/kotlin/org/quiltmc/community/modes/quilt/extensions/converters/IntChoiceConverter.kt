/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.converters

import com.kotlindiscord.kord.extensions.commands.Argument
import com.kotlindiscord.kord.extensions.commands.CommandContext
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.ChoiceConverter
import com.kotlindiscord.kord.extensions.commands.converters.Validator
import com.kotlindiscord.kord.extensions.modules.annotations.converters.Converter
import com.kotlindiscord.kord.extensions.modules.annotations.converters.ConverterType
import com.kotlindiscord.kord.extensions.parser.StringParser
import dev.kord.core.entity.interaction.IntegerOptionValue
import dev.kord.core.entity.interaction.OptionValue
import dev.kord.rest.builder.interaction.IntegerOptionBuilder
import dev.kord.rest.builder.interaction.OptionsBuilder

@Converter(
	"int",

	types = [ConverterType.CHOICE, ConverterType.DEFAULTING, ConverterType.OPTIONAL, ConverterType.SINGLE]
)
class IntChoiceConverter(
	choices: Map<String, Int>,
	override var validator: Validator<Int> = null
) : ChoiceConverter<Int>(choices) {
	override val signatureTypeString = "converters.number.signatureType"

	override suspend fun parse(parser: StringParser?, context: CommandContext, named: String?): Boolean {
        val arg = named ?: parser?.parseNext()?.data ?: return false
        parsed = arg.toIntOrNull() ?: return false
        return true
	}

	override suspend fun toSlashOption(arg: Argument<*>): OptionsBuilder =
        IntegerOptionBuilder(arg.displayName, arg.description).apply {
            required = true
            this@IntChoiceConverter.choices.forEach {
                choice(it.key, it.value.toLong())
            }
        }

	override suspend fun parseOption(context: CommandContext, option: OptionValue<*>): Boolean {
        val optionValue = (option as? IntegerOptionValue)?.value ?: return false
        parsed = optionValue.toInt()
        return true
	}
}
