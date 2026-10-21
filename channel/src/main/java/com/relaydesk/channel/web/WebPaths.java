package com.relaydesk.channel.web;

import jakarta.servlet.http.HttpServletRequest;

/** Path helpers that work both in the servlet container and in standalone MockMvc tests
 * (where {@code getServletPath()} is empty and only {@code getRequestURI()} is populated). */
final class WebPaths {

    private WebPaths() {
    }

    static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri;
    }
}
