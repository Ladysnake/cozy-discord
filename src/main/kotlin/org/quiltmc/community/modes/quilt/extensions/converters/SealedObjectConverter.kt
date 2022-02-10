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
import dev.kord.core.entity.interaction.OptionValue
import dev.kord.rest.builder.interaction.OptionsBuilder
import dev.kord.rest.builder.interaction.StringChoiceBuilder
import kotlin.reflect.KClass

@Converter(
    "sealedObject",

    types = [ConverterType.LIST, ConverterType.OPTIONAL, ConverterType.SINGLE, ConverterType.CHOICE],

    // yes the lack of a space between "T" and ":" is necessary because annotation processing fails otherwise
    builderGeneric = "T: Any",
    builderConstructorArguments = [
        "public val clazz: KClass<T>",
    ],
    functionGeneric = "T: Any",
    functionBuilderArguments = [
        "clazz = T::class",
    ],
    imports = [
        "kotlin.reflect.KClass",
    ],
)
class SealedObjectConverter<T : Any> (
    clazz: KClass<T>,
    choices: Map<String, T>,
    override var validator: Validator<T> = null
) : ChoiceConverter<T>(constructChoices(clazz)) {
    override val signatureTypeString get() = error("No signature type for sealed object converter")

    override suspend fun parse(parser: StringParser?, context: CommandContext, named: String?): Boolean {
        val arg = named ?: parser?.parseNext()?.data ?: return false
        parsed = choices.entries.find { it.key == arg }?.value ?: return false
        return true
    }

    override suspend fun parseOption(context: CommandContext, option: OptionValue<*>): Boolean {
        val arg = (option as? OptionValue.StringOptionValue)?.value ?: return false
        return parse(null, context, arg)
    }

    override suspend fun toSlashOption(arg: Argument<*>): OptionsBuilder =
        StringChoiceBuilder(arg.displayName, arg.description).also {
            it.required = this.required
            this.choices.forEach { (name, _) ->
                it.choice(name, name)
            }
        }

    companion object {
        fun <T : Any> constructChoices(clazz: KClass<T>): Map<String, T> =
            clazz.sealedSubclasses
                .filter { it.objectInstance != null }
                .associate { it.simpleName!! to it.objectInstance!! }
    }
}
