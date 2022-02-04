/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.modes.quilt.extensions.rotatinglog

import dev.kord.common.entity.optional.Optional
import dev.kord.core.entity.Member

class MemberDiff(new: Member, private val old: Member) {
    // user data (not member data)
    val username by old.data::username.comparator(new.data.username)
    val discriminator by old.data::discriminator.comparator(new.data.discriminator)
    val avatar by old.data::avatar.comparator(new.data.avatar)
    val publicFlags by old.data::publicFlags.comparator(new.data.publicFlags)
    val banner by old.data::banner.comparator(new.data.banner)
    val accentColor by old.data::accentColor.comparator(new.data.accentColor)

    // member data
    val nickname by old.memberData::nick.comparator(new.memberData.nick)
    val roles by old.memberData::roles.comparator(new.memberData.roles)
    val premiumSince by old.memberData::premiumSince.comparator(new.memberData.premiumSince)
    val pending by old.memberData::pending.comparator(new.memberData.pending)
    val serverAvatar by old.memberData::avatar.comparator(new.memberData.avatar)
    val timeoutTime by old.memberData::communicationDisabledUntil.comparator(new.memberData.communicationDisabledUntil)

    val isIdentical: Boolean
        get() =
            username is Optional.Missing &&
            discriminator is Optional.Missing &&
            avatar is Optional.Missing &&
            publicFlags is Optional.Missing &&
            banner is Optional.Missing &&
            accentColor is Optional.Missing &&
            nickname is Optional.Missing &&
            roles is Optional.Missing &&
            premiumSince is Optional.Missing &&
            pending is Optional.Missing &&
            serverAvatar is Optional.Missing &&
            timeoutTime is Optional.Missing
}
