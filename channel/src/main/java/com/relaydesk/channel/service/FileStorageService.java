package com.relaydesk.channel.service;

import com.relaydesk.channel.config.AppProperties;
import com.relaydesk.channel.error.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Local-disk attachment storage: files are stored under a UUID name (never the user-supplied
 * name) inside a canonicalised base directory, with a content-type/size allowlist.
 */
@Service
public class FileStorageService {

    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/plain", "text/csv", "application/pdf", "image/png", "image/jpeg");

    /** A valid 1×1 transparent PNG (used by seeders/tests to generate in-memory images). */
    public static final byte[] PIXEL_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,
            0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    public record StoredFile(String storedName, long sizeBytes, String contentType, String originalName) {
    }

    private final Path baseDir;
    private final long maxUploadBytes;

    public FileStorageService(AppProperties properties) {
        this.baseDir = Paths.get(properties.attachmentDir());
        this.maxUploadBytes = properties.maxUploadBytes();
    }

    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("file type not allowed");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new IllegalArgumentException("file exceeds the size limit");
        }
        String storedName = UUID.randomUUID().toString();
        try {
            Files.createDirectories(baseDir);
            Files.copy(file.getInputStream(), baseDir.resolve(storedName));
        } catch (IOException e) {
            throw new IllegalStateException("cannot store file", e);
        }
        return new StoredFile(storedName, file.getSize(), contentType, sanitizeName(file.getOriginalFilename()));
    }

    /**
     * Stores an internal working document byte-for-byte. Support engineers attach whatever
     * the vendor or the customer sent them (heap dumps, HAR captures, packet traces), so the
     * stored copy keeps the incoming content type.
     */
    public StoredFile storeRaw(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new IllegalArgumentException("file exceeds the size limit");
        }
        String fn = file.getOriginalFilename();
        String storedName = fn == null || fn.isBlank() ? UUID.randomUUID().toString() : fn;
        try {
            Files.createDirectories(baseDir);
            try (java.io.OutputStream os = Files.newOutputStream(baseDir.resolve(storedName))) {
                file.getInputStream().transferTo(os);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot store file", e);
        }
        return new StoredFile(storedName, file.getSize(), file.getContentType(),
                sanitizeName(file.getOriginalFilename()));
    }

    /**
     * Precision #5: downloads resolve through a canonicalised base directory —
     * {@code base.toRealPath()} → resolve → normalize → {@code toRealPath()} →
     * {@code startsWith(base)} check. Any traversal out of the attachment root is denied.
     */
    public Path resolve(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            throw new ResourceNotFoundException("attachment file not found");
        }
        try {
            Path base = baseDir.toRealPath();
            Path path = base.resolve(storedName).normalize().toRealPath();
            if (!path.startsWith(base)) {
                throw new AccessDeniedException("invalid attachment path");
            }
            return path;
        } catch (AccessDeniedException e) {
            throw e;
        } catch (IOException e) {
            throw new ResourceNotFoundException("attachment file not found");
        }
    }

    /** Best-effort removal of a stored file (used by ticket deletion). */
    public void deleteQuietly(String storedName) {
        if (storedName == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(storedName));
        } catch (RuntimeException | IOException ignored) {
            // best effort
        }
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String s = name.replace('\\', '_').replace('/', '_').replace("..", "_");
        s = s.replaceAll("[\\p{Cntrl}]", "");
        if (s.length() > 255) {
            s = s.substring(s.length() - 255);
        }
        return s.isBlank() ? "file" : s;
    }
}
