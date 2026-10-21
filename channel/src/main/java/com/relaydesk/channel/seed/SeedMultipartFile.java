package com.relaydesk.channel.seed;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In-memory {@link MultipartFile} used by the seeder to generate attachment uploads without
 * going through HTTP. Content is plain text/CSV produced by {@link SeedData}.
 */
record SeedMultipartFile(String name, String originalFilename, String contentType, byte[] bytes)
        implements MultipartFile {

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes == null || bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes == null ? 0 : bytes.length;
    }

    @Override
    public byte[] getBytes() {
        return bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        transferTo(dest.toPath());
    }

    @Override
    public void transferTo(Path dest) throws IOException {
        Files.write(dest, bytes == null ? new byte[0] : bytes);
    }
}
