package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.UserInvite;

/** Result of creating an invite: the invite plus the accept link (previewed in the UI). */
public record InviteCreated(UserInvite invite, String link) {
}
