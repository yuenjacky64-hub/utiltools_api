package com.example.utiltools.crypto.service;

import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CryptoService {

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    public HashResponse hash(HashRequest request) {
        String rawAlgorithm = request.algorithm().trim().toUpperCase(Locale.ROOT);
        String standardAlg = normalizeAlgorithm(rawAlgorithm);

        byte[] contentBytes = request.content().getBytes(StandardCharsets.UTF_8);

        if (request.secretKey() != null && !request.secretKey().isBlank()) {
            String hmacAlg = "Hmac" + standardAlg.replace("-", "");
            try {
                Mac mac = Mac.getInstance(hmacAlg);
                SecretKeySpec secretKey = new SecretKeySpec(request.secretKey().getBytes(StandardCharsets.UTF_8), hmacAlg);
                mac.init(secretKey);
                byte[] hmacBytes = mac.doFinal(contentBytes);
                return new HashResponse(hmacAlg, HEX_FORMAT.formatHex(hmacBytes), true);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to generate HMAC with algorithm " + hmacAlg + ": " + e.getMessage(), e);
            }
        } else {
            try {
                MessageDigest md = MessageDigest.getInstance(standardAlg);
                byte[] digestBytes = md.digest(contentBytes);
                return new HashResponse(standardAlg, HEX_FORMAT.formatHex(digestBytes), false);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("Unsupported hash algorithm: " + rawAlgorithm, e);
            }
        }
    }

    public Base64Response base64(Base64Request request) {
        boolean urlSafe = Boolean.TRUE.equals(request.urlSafe());
        String action = request.action().trim().toUpperCase(Locale.ROOT);

        if ("ENCODE".equals(action)) {
            Base64.Encoder encoder = urlSafe ? Base64.getUrlEncoder() : Base64.getEncoder();
            String encoded = encoder.encodeToString(request.content().getBytes(StandardCharsets.UTF_8));
            return new Base64Response("ENCODE", encoded);
        } else if ("DECODE".equals(action)) {
            Base64.Decoder decoder = urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder();
            try {
                byte[] decodedBytes = decoder.decode(request.content().trim());
                String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
                return new Base64Response("DECODE", decoded);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid Base64 content: " + e.getMessage(), e);
            }
        } else {
            throw new IllegalArgumentException("Invalid action: " + request.action() + ". Expected ENCODE or DECODE.");
        }
    }

    public UuidResponse generateUuids(int count, boolean compact) {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("UUID count must be between 1 and 100");
        }
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = UUID.randomUUID();
            result.add(compact ? uuid.toString().replace("-", "") : uuid.toString());
        }
        return new UuidResponse(count, result);
    }

    private String normalizeAlgorithm(String raw) {
        return switch (raw) {
            case "SHA256", "SHA-256" -> "SHA-256";
            case "SHA512", "SHA-512" -> "SHA-512";
            case "SHA1", "SHA-1" -> "SHA-1";
            case "MD5" -> "MD5";
            default -> throw new IllegalArgumentException("Unsupported hash algorithm: " + raw + ". Supported: SHA-256, SHA-512, SHA-1, MD5.");
        };
    }
}
