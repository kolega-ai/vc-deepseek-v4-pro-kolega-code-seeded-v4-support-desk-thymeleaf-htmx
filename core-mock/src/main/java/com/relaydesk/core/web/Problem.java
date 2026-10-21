package com.relaydesk.core.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Problem+json error body of the vendor system: {@code {"code","message","detail"}}.
 * The {@code code} (CORE_1xxx) is the stable contract callers switch on.
 */
public record Problem(String code, String message, String detail) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Writes a problem+json body directly to the response (used by servlet filters, before MVC runs). */
    public static void write(HttpServletResponse response, int status, String code, String message, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(), new Problem(code, message, detail));
    }
}
