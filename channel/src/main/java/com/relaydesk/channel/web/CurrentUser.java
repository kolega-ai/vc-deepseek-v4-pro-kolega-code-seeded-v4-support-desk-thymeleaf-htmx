package com.relaydesk.channel.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the decision-derived {@code RequestPrincipal} (request attribute
 * {@code requestPrincipal}) to a controller parameter. Null for public pages.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
