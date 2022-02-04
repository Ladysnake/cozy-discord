/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.rotatinglog

import dev.kord.common.entity.optional.Optional
import dev.kord.core.entity.Guild

class GuildDiff(new: Guild, private val old: Guild) {
    val name by old.data::name.comparator(new.data.name)
    val icon by old.data::icon.comparator(new.data.icon)
    val iconHash by old.data::iconHash.comparator(new.data.iconHash)
    val splash by old.data::splash.comparator(new.data.splash)
    val discoverySplash by old.data::discoverySplash.comparator(new.data.discoverySplash)
    val ownerId by old.data::ownerId.comparator(new.data.ownerId)
    val permissions by old.data::permissions.comparator(new.data.permissions)
    val afkChannelId by old.data::afkChannelId.comparator(new.data.afkChannelId)
    val afkTimeout by old.data::afkTimeout.comparator(new.data.afkTimeout)
    val widgetEnabled by old.data::widgetEnabled.comparator(new.data.widgetEnabled)
    val widgetChannelId by old.data::widgetChannelId.comparator(new.data.widgetChannelId)
    val verificationLevel by old.data::verificationLevel.comparator(new.data.verificationLevel)
    val defaultMessageNotifications by old.data::defaultMessageNotifications.comparator(
        new.data.defaultMessageNotifications
    )
    val explicitContentFilter by old.data::explicitContentFilter.comparator(
        new.data.explicitContentFilter
    )
    val roles by old.data::roles.comparator(new.data.roles)
    val emojis by old.data::emojis.comparator(new.data.emojis)
    val features by old.data::features.comparator(new.data.features)
    val mfaLevel by old.data::mfaLevel.comparator(new.data.mfaLevel)
    val applicationId by old.data::applicationId.comparator(new.data.applicationId)
    val systemChannelId by old.data::systemChannelId.comparator(new.data.systemChannelId)
    val systemChannelFlags by old.data::systemChannelFlags.comparator(new.data.systemChannelFlags)
    val rulesChannelId by old.data::rulesChannelId.comparator(new.data.rulesChannelId)
    val joinedAt by old.data::joinedAt.comparator(new.data.joinedAt)
    val large by old.data::large.comparator(new.data.large)
    val memberCount by old.data::memberCount.comparator(new.data.memberCount)
    val channels by old.data::channels.comparator(new.data.channels)
    val maxPresences by old.data::maxPresences.comparator(new.data.maxPresences)
    val maxMembers by old.data::maxMembers.comparator(new.data.maxMembers)
    val vanityUrlCode by old.data::vanityUrlCode.comparator(new.data.vanityUrlCode)
    val description by old.data::description.comparator(new.data.description)
    val banner by old.data::banner.comparator(new.data.banner)
    val premiumTier by old.data::premiumTier.comparator(new.data.premiumTier)
    val premiumSubscriptionCount by old.data::premiumSubscriptionCount.comparator(
        new.data.premiumSubscriptionCount
    )
    val preferredLocale by old.data::preferredLocale.comparator(new.data.preferredLocale)
    val publicUpdatesChannelId by old.data::publicUpdatesChannelId.comparator(
        new.data.publicUpdatesChannelId
    )
    val maxVideoChannelUsers by old.data::maxVideoChannelUsers.comparator(
        new.data.maxVideoChannelUsers
    )
    val approximateMemberCount by old.data::approximateMemberCount.comparator(
        new.data.approximateMemberCount
    )
    val approximatePresenceCount by old.data::approximatePresenceCount.comparator(
        new.data.approximatePresenceCount
    )
    val welcomeScreen by old.data::welcomeScreen.comparator(new.data.welcomeScreen)
    val nsfwLevel by old.data::nsfwLevel.comparator(new.data.nsfwLevel)
    val threads by old.data::threads.comparator(new.data.threads)

    val isIdentical: Boolean
        get() =
            name is Optional.Missing &&
            icon is Optional.Missing &&
            iconHash is Optional.Missing &&
            splash is Optional.Missing &&
            discoverySplash is Optional.Missing &&
            ownerId is Optional.Missing &&
            permissions is Optional.Missing &&
            afkChannelId is Optional.Missing &&
            afkTimeout is Optional.Missing &&
            widgetEnabled is Optional.Missing &&
            widgetChannelId is Optional.Missing &&
            verificationLevel is Optional.Missing &&
            defaultMessageNotifications is Optional.Missing &&
            explicitContentFilter is Optional.Missing &&
            roles is Optional.Missing &&
            emojis is Optional.Missing &&
            features is Optional.Missing &&
            mfaLevel is Optional.Missing &&
            applicationId is Optional.Missing &&
            systemChannelId is Optional.Missing &&
            systemChannelFlags is Optional.Missing &&
            rulesChannelId is Optional.Missing &&
            joinedAt is Optional.Missing &&
            large is Optional.Missing &&
            memberCount is Optional.Missing &&
            channels is Optional.Missing &&
            maxPresences is Optional.Missing &&
            maxMembers is Optional.Missing &&
            vanityUrlCode is Optional.Missing &&
            description is Optional.Missing &&
            banner is Optional.Missing &&
            premiumTier is Optional.Missing &&
            premiumSubscriptionCount is Optional.Missing &&
            preferredLocale is Optional.Missing &&
            publicUpdatesChannelId is Optional.Missing &&
            maxVideoChannelUsers is Optional.Missing &&
            approximateMemberCount is Optional.Missing &&
            approximatePresenceCount is Optional.Missing &&
            welcomeScreen is Optional.Missing &&
            nsfwLevel is Optional.Missing &&
            threads is Optional.Missing
}
