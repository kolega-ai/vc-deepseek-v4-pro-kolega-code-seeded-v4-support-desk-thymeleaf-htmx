package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.UserAccount;

/** Result of a successful password login. */
public record LoginOutcome(UserAccount user, String sessionToken, RequestPrincipal principal) {
}
