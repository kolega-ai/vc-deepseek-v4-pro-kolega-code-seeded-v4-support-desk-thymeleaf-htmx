package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.Attachment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * CSV and attachment download responses. CSV files are served as UTF-8 attachments with
 * {@code nosniff}; attachment files keep their stored content type — images inline, everything
 * else as a download — with {@code nosniff} (UUID-named files, no SVG is ever stored).
 */
final class Downloads {

    private Downloads() {
    }

    static ResponseEntity<byte[]> csv(String filename, String content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName(filename) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    static ResponseEntity<Resource> file(Attachment meta, Path path) {
        String contentType = meta.getContentType() == null
                ? "application/octet-stream" : meta.getContentType().toLowerCase(Locale.ROOT);
        boolean inline = contentType.equals("image/png") || contentType.equals("image/jpeg");
        String disposition = (inline ? "inline" : "attachment")
                + "; filename=\"" + safeName(meta.getOriginalName()) + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(path));
    }

    /** Filenames go into a quoted header value — quotes and control characters are stripped. */
    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        return name.replace('"', '\'').replaceAll("[\\p{Cntrl}]", "");
    }
}
