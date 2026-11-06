package com.example.utiltools.crypto.service;

import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import com.example.utiltools.crypto.dto.UrlRequest;
import com.example.utiltools.crypto.dto.UrlResponse;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CryptoService {

    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private static final String UUID_VERSION_V4 = "V4";
    private static final String UUID_VERSION_V7 = "V7";

    private static final long SEQ_BITS = 16;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
    private static final long MILLIS_MASK = (1L << 48) - 1;
    private static final long LOW_58_MASK = (1L << 58) - 1;
    private static final long UUID_V7_VERSION_BITS = 0x7000L;
    private static final long UUID_V7_VARIANT_BITS = 0x8000000000000000L;
    private static final long NEVER_MINTED = -1L;

    private final Clock clock;
    private final AtomicLong counter = new AtomicLong(NEVER_MINTED);

    public CryptoService() {
        this(Clock.systemUTC());
    }

    public CryptoService(Clock clock) {
        this.clock = clock;
    }

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

    public UrlResponse url(UrlRequest request) {
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        String mode = resolveUrlMode(request.mode());

        if ("ENCODE".equals(action)) {
            return new UrlResponse("ENCODE", mode, encodeUrl(request.content(), mode));
        } else if ("DECODE".equals(action)) {
            return new UrlResponse("DECODE", mode, decodeUrl(request.content(), mode));
        } else {
            throw new IllegalArgumentException("Invalid action: " + request.action() + ". Expected ENCODE or DECODE.");
        }
    }

    private String resolveUrlMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return "COMPONENT";
        }
        String mode = rawMode.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "COMPONENT", "PATH", "FORM" -> mode;
            default -> throw new IllegalArgumentException(
                    "Invalid mode: " + rawMode + ". Expected COMPONENT, PATH or FORM.");
        };
    }

    private String encodeUrl(String content, String mode) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 2);
        for (byte raw : bytes) {
            int value = raw & 0xFF;
            if (isUnreservedUrlByte(value) || ("PATH".equals(mode) && value == '/')) {
                encoded.append((char) value);
            } else if (value == ' ' && "FORM".equals(mode)) {
                encoded.append('+');
            } else {
                encoded.append('%')
                        .append(HEX_DIGITS[value >>> 4])
                        .append(HEX_DIGITS[value & 0x0F]);
            }
        }
        return encoded.toString();
    }

    private String decodeUrl(String content, String mode) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(bytes.length);
        int index = 0;
        while (index < bytes.length) {
            int value = bytes[index] & 0xFF;
            if (value == '%') {
                if (index + 2 >= bytes.length) {
                    throw new IllegalArgumentException(
                            "Invalid percent-encoding at index " + index + ": truncated escape sequence");
                }
                int high = hexValue(bytes[index + 1]);
                int low = hexValue(bytes[index + 2]);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException(
                            "Invalid percent-encoding at index " + index + ": expected two hexadecimal digits");
                }
                decoded.write((high << 4) | low);
                index += 3;
            } else if (value == '+' && "FORM".equals(mode)) {
                decoded.write(' ');
                index++;
            } else {
                decoded.write(value);
                index++;
            }
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 sequence in decoded content: " + e.getMessage(), e);
        }
    }

    private static boolean isUnreservedUrlByte(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static int hexValue(byte raw) {
        int value = raw & 0xFF;
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        return -1;
    }

    public UuidResponse generateUuids(int count, boolean compact) {
        return generateUuids(count, compact, UUID_VERSION_V4);
    }

    public UuidResponse generateUuids(int count, boolean compact, String version) {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("UUID count must be between 1 and 100");
        }
        String resolvedVersion = version == null ? UUID_VERSION_V4 : version.trim().toUpperCase(Locale.ROOT);
        if (!UUID_VERSION_V4.equals(resolvedVersion) && !UUID_VERSION_V7.equals(resolvedVersion)) {
            throw new IllegalArgumentException("Invalid version: " + version + ". Expected V4 or V7.");
        }
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = UUID_VERSION_V7.equals(resolvedVersion) ? generateUuidV7() : UUID.randomUUID();
            result.add(compact ? uuid.toString().replace("-", "") : uuid.toString());
        }
        return new UuidResponse(count, result, resolvedVersion);
    }

    public UUID generateUuidV7() {
        long millis = clock.millis() & MILLIS_MASK;
        long current;
        long next;
        do {
            current = counter.get();
            long currentMillis = current < 0 ? -1 : (current >>> SEQ_BITS);
            long currentSeq = current < 0 ? SEQ_MASK : (current & SEQ_MASK);
            if (currentMillis < millis) {
                next = millis << SEQ_BITS;
            } else if (currentSeq < SEQ_MASK) {
                next = current + 1;
            } else {
                next = (currentMillis + 1) << SEQ_BITS;
            }
        } while (!counter.compareAndSet(current, next));

        long sequenceMillis = next >>> SEQ_BITS;
        long sequence = next & SEQ_MASK;

        long mostSignificantBits = (sequenceMillis << 16)
                | UUID_V7_VERSION_BITS
                | ((sequence >>> 4) & 0x0FFFL);
        long leastSignificantBits = UUID_V7_VARIANT_BITS
                | ((sequence & 0x0FL) << 58)
                | (ThreadLocalRandom.current().nextLong() & LOW_58_MASK);
        return new UUID(mostSignificantBits, leastSignificantBits);
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
