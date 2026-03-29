package com.example.ossdoc.domain.graphstore.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class SymbolIdGenerator {
    public String generate(String runId, String qualifiedName) {
        try {
            String input = runId + ":" + qualifiedName;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder("sym_");
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate symbol id", e);
        }
    }
}