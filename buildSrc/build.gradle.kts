/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

plugins {
	`kotlin-dsl`
}

// poor man's version catalog
val versionFile = project.file("../libs.versions.toml")
val versionFileContents = versionFile.readText()

val versions = versionFileContents
	.substringAfter("[versions]")
	.substringBefore("[")
	.trim()
	.split("\n")
	.filter { it.isNotBlank() }
	.map { it.trim() }
	.map { it.split("=") }
	.associate { (k, v) -> k.trim() to v.substringAfter('"').substringBefore('"') }

repositories {
	google()
	gradlePluginPortal()
}

dependencies {
	implementation(gradleApi())
	implementation(localGroovy())

	implementation(kotlin("gradle-plugin", version = versions["kotlin"]))
	implementation(kotlin("serialization", version = versions["kotlin"]))

	implementation("gradle.plugin.org.cadixdev.gradle", "licenser", versions["gradle-licenser"])
	implementation("com.github.jakemarsden", "git-hooks-gradle-plugin", versions["git-hooks-gradle-plugin"])
	implementation("com.google.devtools.ksp", "com.google.devtools.ksp.gradle.plugin", versions["ksp"])
	implementation("io.gitlab.arturbosch.detekt", "detekt-gradle-plugin", versions["detekt"])
//	implementation("org.ec4j.editorconfig", "org.ec4j.editorconfig.gradle.plugin", versions["editorconfig-gradle-plugin"])

	implementation("com.expediagroup.graphql", "com.expediagroup.graphql.gradle.plugin", versions["graphql"])
	implementation("com.github.johnrengelman.shadow", "com.github.johnrengelman.shadow.gradle.plugin", versions["shadow-gradle-plugin"])
}
