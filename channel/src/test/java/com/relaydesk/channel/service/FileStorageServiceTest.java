package com.relaydesk.channel.service;

import com.relaydesk.channel.config.AppProperties;
import com.relaydesk.channel.error.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStorageServiceTest {

    @TempDir
    Path temp;

    private AppProperties props(Path dir) {
        return new AppProperties("secret", "http://decision", "dkey", "http://core", "ckey",
                dir.toString(), false, 10L * 1024 * 1024);
    }

    @Test
    void storeAndResolveRoundTrip() throws Exception {
        FileStorageService svc = new FileStorageService(props(temp));
        byte[] content = "a,b\n1,2".getBytes(StandardCharsets.UTF_8);
        FileStorageService.StoredFile stored = svc.store(
                new MockMultipartFile("file", "report.csv", "text/csv", content));
        Path resolved = svc.resolve(stored.storedName());
        assertTrue(Files.exists(resolved));
        assertEquals("a,b\n1,2", Files.readString(resolved));
        assertEquals("report.csv", stored.originalName());
        assertEquals(content.length, stored.sizeBytes());
    }

    @Test
    void sanitizesDangerousOriginalName() {
        FileStorageService svc = new FileStorageService(props(temp));
        FileStorageService.StoredFile stored = svc.store(
                new MockMultipartFile("file", "../../../etc/passwd", "text/plain", "x".getBytes()));
        assertFalse(stored.originalName().contains("/"));
        assertFalse(stored.originalName().contains(".."));
    }

    @Test
    void traversalOutsideBaseDenied() throws Exception {
        Path base = temp.resolve("attachments");
        Files.createDirectories(base);
        Files.writeString(temp.resolve("evil.txt"), "evil");
        FileStorageService svc = new FileStorageService(props(base));
        assertThrows(AccessDeniedException.class, () -> svc.resolve("../evil.txt"));
    }

    @Test
    void missingFileYieldsNotFound() {
        FileStorageService svc = new FileStorageService(props(temp));
        assertThrows(ResourceNotFoundException.class, () -> svc.resolve("does-not-exist"));
    }

    @Test
    void disallowedContentTypeRejected() {
        FileStorageService svc = new FileStorageService(props(temp));
        assertThrows(IllegalArgumentException.class,
                () -> svc.store(new MockMultipartFile("f", "x.svg", "image/svg+xml", "<svg/>".getBytes())));
    }

    @Test
    void oversizedFileRejected() {
        // Service-level cap of 16 bytes; the customer-specific limit is enforced by the caller.
        AppProperties small = new AppProperties("secret", "http://decision", "dkey",
                "http://core", "ckey", temp.toString(), false, 16);
        FileStorageService svc = new FileStorageService(small);
        byte[] big = new byte[1024];
        assertThrows(IllegalArgumentException.class,
                () -> svc.store(new MockMultipartFile("f", "big.csv", "text/csv", big)));
    }
}
