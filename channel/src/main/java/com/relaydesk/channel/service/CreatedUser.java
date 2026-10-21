package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.UserAccount;

/** Result of creating a support user: the account plus the one-time initial password. */
public record CreatedUser(UserAccount user, String initialPassword) {
}
