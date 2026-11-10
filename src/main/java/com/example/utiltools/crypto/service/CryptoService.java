package com.example.utiltools.crypto.service;

import com.example.utiltools.crypto.dto.Base32Request;
import com.example.utiltools.crypto.dto.Base32Response;
import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.dto.JwtDecodeRequest;
import com.example.utiltools.crypto.dto.JwtDecodeResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import com.example.utiltools.crypto.dto.UrlRequest;
import com.example.utiltools.crypto.dto.UrlResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Arrays;
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

    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int BASE32_BLOCK_CHARS = 8;
    private static final int[] BASE32_SPARE_BITS = {0, 0, 2, 0, 4, 1, 0, 3};
    private static final int[] BASE32_DECODE_TABLE = new int[128];

    static {
        Arrays.fill(BASE32_DECODE_TABLE, -1);
        for (int i = 0; i < BASE32_ALPHABET.length; i++) {
            BASE32_DECODE_TABLE[BASE32_ALPHABET[i]] = i;
            BASE32_DECODE_TABLE[Character.toLowerCase(BASE32_ALPHABET[i])] = i;
        }
    }

    private static final String UUID_VERSION_V4 = "V4";
    private static final String UUID_VERSION_V7 = "V7";
    private static final String UUID_VERSION_V8 = "V8";

    private static final long SEQ_BITS = 16;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
    private static final long MILLIS_MASK = (1L << 48) - 1;
    private static final long LOW_58_MASK = (1L << 58) - 1;
    private static final long UUID_V7_VERSION_BITS = 0x7000L;
    private static final long UUID_V7_VARIANT_BITS = 0x8000000000000000L;
    private static final long UUID_V8_VERSION_BITS = 0x8000L;
    private static final long UUID_V8_VARIANT_BITS = 0x8000000000000000L;
    private static final long NEVER_MINTED = -1L;

    private static final int JWT_MAX_TOKEN_LENGTH = 16384;
    private static final int JWT_SEGMENT_COUNT = 3;
    private static final String JWT_SEGMENT_SEPARATOR_REGEX = "\\.";

    private static final ObjectMapper JWT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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

    public Base32Response base32(Base32Request request) {
        String action = request.action().trim().toUpperCase(Locale.ROOT);

        if ("ENCODE".equals(action)) {
            byte[] bytes = request.content().getBytes(StandardCharsets.UTF_8);
            return new Base32Response("ENCODE", encodeBase32(bytes));
        } else if ("DECODE".equals(action)) {
            return new Base32Response("DECODE", decodeBase32(request.content()));
        } else {
            throw new IllegalArgumentException("Invalid action: " + request.action() + ". Expected ENCODE or DECODE.");
        }
    }

    /**
     * Canonical RFC 4648 Base32: uppercase alphabet A-Z2-7, '=' padding to a multiple of 8 characters.
     */
    private String encodeBase32(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        StringBuilder encoded = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte raw : bytes) {
            buffer = (buffer << 8) | (raw & 0xFF);
            bits += 8;
            while (bits >= 5) {
                encoded.append(BASE32_ALPHABET[(buffer >>> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            encoded.append(BASE32_ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        while (encoded.length() % BASE32_BLOCK_CHARS != 0) {
            encoded.append('=');
        }
        return encoded.toString();
    }

    /**
     * Tolerant on form (case, optional padding) but strict on validity: the alphabet, padding
     * placement, group length, non-zero trailing bits and the decoded UTF-8 sequence are all gated.
     */
    private String decodeBase32(String content) {
        String input = content.trim();
        int end = input.length();
        while (end > 0 && input.charAt(end - 1) == '=') {
            end--;
        }
        String body = input.substring(0, end);

        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '=') {
                // Deferred to the padding gate below so '=' is reported as misplaced padding,
                // while 'MZXW6YTBOI=====!' still fails on the alphabet gate first.
                continue;
            }
            if (ch >= 128 || BASE32_DECODE_TABLE[ch] < 0) {
                throw new IllegalArgumentException(
                        "Invalid Base32 content: unexpected character '" + ch + "' at index " + i);
            }
        }
        if (body.indexOf('=') >= 0) {
            throw new IllegalArgumentException(
                    "Invalid Base32 content: misplaced padding '=' inside the encoded body");
        }
        if (!isLegalBase32Length(body.length())) {
            throw new IllegalArgumentException("Invalid Base32 content: invalid length " + body.length()
                    + "; Base32 data must be a multiple of 8 characters or end after 2, 4, 5 or 7 characters");
        }
        int padding = input.length() - end;
        if (padding > 0 && padding != expectedBase32Padding(body.length())) {
            throw new IllegalArgumentException("Invalid Base32 content: incorrect padding; expected "
                    + expectedBase32Padding(body.length()) + " '=' characters but found " + padding);
        }

        int spareBits = BASE32_SPARE_BITS[body.length() % BASE32_BLOCK_CHARS];
        if (!body.isEmpty() && spareBits > 0) {
            int last = BASE32_DECODE_TABLE[body.charAt(body.length() - 1)];
            if ((last & ((1 << spareBits) - 1)) != 0) {
                throw new IllegalArgumentException("Invalid Base32 content: non-zero trailing bits "
                        + "in the final character '" + body.charAt(body.length() - 1) + "'");
            }
        }

        byte[] decoded = new byte[body.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int written = 0;
        for (int i = 0; i < body.length(); i++) {
            buffer = (buffer << 5) | BASE32_DECODE_TABLE[body.charAt(i)];
            bits += 5;
            if (bits >= 8) {
                decoded[written++] = (byte) ((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(decoded)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "Invalid Base32 content: invalid UTF-8 sequence in decoded content: " + e.getMessage(), e);
        }
    }

    private static int expectedBase32Padding(int length) {
        return switch (length % BASE32_BLOCK_CHARS) {
            case 0 -> 0;
            case 2 -> 6;
            case 4 -> 4;
            case 5 -> 3;
            default -> 1;
        };
    }

    private static boolean isLegalBase32Length(int length) {
        return switch (length % BASE32_BLOCK_CHARS) {
            case 0, 2, 4, 5, 7 -> true;
            default -> false;
        };
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
        if (!UUID_VERSION_V4.equals(resolvedVersion)
                && !UUID_VERSION_V7.equals(resolvedVersion)
                && !UUID_VERSION_V8.equals(resolvedVersion)) {
            throw new IllegalArgumentException("Invalid version: " + version + ". Expected V4, V7 or V8.");
        }
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = switch (resolvedVersion) {
                case UUID_VERSION_V7 -> generateUuidV7();
                case UUID_VERSION_V8 -> generateUuidV8();
                default -> UUID.randomUUID();
            };
            result.add(compact ? uuid.toString().replace("-", "") : uuid.toString());
        }
        return new UuidResponse(count, result, resolvedVersion);
    }

    public UUID generateUuidV7() {
        long next = nextMonotonicState();
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

    /**
     * Mints the next state of the shared per-process monotonic {@link #counter}: the upper 48 bits
     * hold the unix-millisecond timestamp (never moving backwards) and the low 16 bits hold a
     * strictly ascending sequence that rolls over into the millisecond on saturation.
     *
     * @return the claimed {@code (millis << 16) | sequence} state
     */
    private long nextMonotonicState() {
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
        return next;
    }

    /**
     * Mints a time-ordered RFC 9562 UUIDv8 from the shared monotonic counter, so ids produced in the
     * same millisecond stay strictly ascending and unique.
     *
     * @return the next UUIDv8
     */
    public UUID generateUuidV8() {
        long next = nextMonotonicState();
        return buildUuidV8(next >>> SEQ_BITS, next & SEQ_MASK);
    }

    /**
     * Builds a UUIDv8 for the current clock reading and the caller-supplied logical counter, whose
     * low 16 bits become the last 16 bits of the id. Pure: it never reads or advances the shared
     * monotonic counter.
     *
     * @param counter caller-supplied logical counter, used modulo {@code 65536}
     * @return the UUIDv8 for {@code (now, counter)}
     */
    public UUID generateUuidV8(long counter) {
        return buildUuidV8(clock.millis(), counter);
    }

    private static UUID buildUuidV8(long millis, long counter) {
        long mostSignificantBits = ((millis & MILLIS_MASK) << 16) | UUID_V8_VERSION_BITS;
        long leastSignificantBits = UUID_V8_VARIANT_BITS | (counter & SEQ_MASK);
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

    /**
     * Decodes a compact JWS/JWT token without verifying its signature.
     *
     * <p>The token is parsed strictly: exactly three dot-separated segments, unpadded base64url
     * for every segment, strict UTF-8, and JSON objects in both the header and the payload.
     * Nothing about the signature is validated — the response always reports {@code verified=false}.
     */
    public JwtDecodeResponse jwtDecode(JwtDecodeRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        if (request.token().length() > JWT_MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "token must not exceed " + JWT_MAX_TOKEN_LENGTH + " characters");
        }
        String[] segments = request.token().split(JWT_SEGMENT_SEPARATOR_REGEX, -1);
        if (segments.length != JWT_SEGMENT_COUNT) {
            throw new IllegalArgumentException("JWT must have exactly three dot-separated segments");
        }
        String headerSegment = segments[0];
        String payloadSegment = segments[1];
        String signatureSegment = segments[2];
        if (headerSegment.isEmpty()) {
            throw new IllegalArgumentException("JWT header segment must not be empty");
        }
        if (payloadSegment.isEmpty()) {
            throw new IllegalArgumentException("JWT payload segment must not be empty");
        }
        if (headerSegment.indexOf('=') >= 0) {
            throw new IllegalArgumentException("JWT header segment must not be base64url padded");
        }
        if (payloadSegment.indexOf('=') >= 0) {
            throw new IllegalArgumentException("JWT payload segment must not be base64url padded");
        }
        if (signatureSegment.indexOf('=') >= 0) {
            throw new IllegalArgumentException("JWT signature segment must not be base64url padded");
        }
        byte[] headerBytes = decodeJwtSegment(headerSegment, "header");
        byte[] payloadBytes = decodeJwtSegment(payloadSegment, "payload");
        byte[] signatureBytes = signatureSegment.isEmpty()
                ? new byte[0]
                : decodeJwtSegment(signatureSegment, "signature");
        String headerText = decodeJwtUtf8(headerBytes, "header");
        String payloadText = decodeJwtUtf8(payloadBytes, "payload");
        JsonNode header = parseJwtSegment(headerText, "header");
        JsonNode payload = parseJwtSegment(payloadText, "payload");

        JsonNode algorithmNode = header.get("alg");
        if (algorithmNode == null || !algorithmNode.isTextual() || algorithmNode.asText().isBlank()) {
            throw new IllegalArgumentException("JWT header.alg is required and must be a non-blank string");
        }
        JsonNode typeNode = header.get("typ");
        if (typeNode != null && !typeNode.isTextual()) {
            throw new IllegalArgumentException("JWT header.typ must be a string when present");
        }
        return new JwtDecodeResponse(
                algorithmNode.asText(),
                typeNode == null ? null : typeNode.asText(),
                header,
                payload,
                !signatureSegment.isEmpty(),
                signatureBytes.length,
                false);
    }

    private byte[] decodeJwtSegment(String segment, String segmentName) {
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "JWT " + segmentName + " segment is not valid unpadded base64url", e);
        }
    }

    private String decodeJwtUtf8(byte[] bytes, String segmentName) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("JWT " + segmentName + " segment is not valid UTF-8", e);
        }
    }

    private JsonNode parseJwtSegment(String text, String segmentName) {
        JsonNode node;
        try {
            node = JWT_MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JWT " + segmentName + " must be a JSON object", e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("JWT " + segmentName + " must be a JSON object");
        }
        return node;
    }
}
