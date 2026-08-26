package com.example.ossdoc.domain.extraction.service.support.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * sha-256 해시 유틸
 */
public final class HashSupport {

    private static final int BUFFER_SIZE = 8192;

    private HashSupport() {
    }

    public static String sha256Hex(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");

        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digest(byte[] bytes) {
        MessageDigest digest = newDigest();
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }
}