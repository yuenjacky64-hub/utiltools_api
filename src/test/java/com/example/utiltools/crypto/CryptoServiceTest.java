package com.example.utiltools.crypto;

import com.example.utiltools.crypto.dto.Base32Request;
import com.example.utiltools.crypto.dto.Base32Response;
import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.dto.JwtDecodeRequest;
import com.example.utiltools.crypto.dto.JwtDecodeResponse;
import com.example.utiltools.crypto.dto.UrlParseRequest;
import com.example.utiltools.crypto.dto.UrlParseResponse;
import com.example.utiltools.crypto.dto.UrlQueryParameter;
import com.example.utiltools.crypto.dto.UrlRequest;
import com.example.utiltools.crypto.dto.UrlResponse;
import com.example.utiltools.crypto.dto.UuidInspectResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import com.example.utiltools.crypto.service.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private static final long FIXED_CLOCK_MILLIS = 0x017F22E279B0L;
    private static final String UUID_V8_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}$";

    private CryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new CryptoService();
    }

    @Test
    @DisplayName("Should generate accurate SHA-256 hash for 'Hello World'")
    void shouldGenerateSha256() {
        HashRequest request = new HashRequest("SHA-256", "Hello World", null);
        HashResponse response = cryptoService.hash(request);

        // Standard SHA-256 of "Hello World"
        assertThat(response.digest()).isEqualTo("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e");
        assertThat(response.algorithm()).isEqualTo("SHA-256");
        assertThat(response.isHmac()).isFalse();
    }

    @Test
    @DisplayName("Should generate accurate HMAC-SHA256 when secretKey is provided")
    void shouldGenerateHmacSha256() {
        HashRequest request = new HashRequest("SHA-256", "Hello World", "secret-key");
        HashResponse response = cryptoService.hash(request);

        assertThat(response.digest()).isNotBlank();
        assertThat(response.isHmac()).isTrue();
        assertThat(response.algorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    @DisplayName("Should generate the NIST SHA-384 vector for 'abc'")
    void hashSha384KnownVector() {
        HashResponse response = cryptoService.hash(new HashRequest("SHA-384", "abc", null));

        assertThat(response.algorithm()).isEqualTo("SHA-384");
        assertThat(response.isHmac()).isFalse();
        assertThat(response.digest())
                .isEqualTo("cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed"
                        + "8086072ba1e7cc2358baeca134c825a7");
    }

    @Test
    @DisplayName("Should treat SHA384 and SHA-384 as the same algorithm and use HmacSHA384 for HMAC")
    void sha384AliasAndHmac() throws Exception {
        HashResponse canonical = cryptoService.hash(new HashRequest("SHA-384", "abc", null));
        HashResponse aliased = cryptoService.hash(new HashRequest("SHA384", "abc", null));

        assertThat(aliased.algorithm()).isEqualTo("SHA-384");
        assertThat(aliased.isHmac()).isFalse();
        assertThat(aliased.digest()).isEqualTo(canonical.digest());

        HashResponse hmac = cryptoService.hash(new HashRequest("SHA384", "abc", "secret-key"));
        assertThat(hmac.algorithm()).isEqualTo("HmacSHA384");
        assertThat(hmac.isHmac()).isTrue();

        Mac reference = Mac.getInstance("HmacSHA384");
        reference.init(new SecretKeySpec("secret-key".getBytes(StandardCharsets.UTF_8), "HmacSHA384"));
        String expected = java.util.HexFormat.of().formatHex(reference.doFinal("abc".getBytes(StandardCharsets.UTF_8)));
        assertThat(hmac.digest()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should generate the NIST SHA-224 vector for 'abc'")
    void hashSha224KnownVector() {
        HashResponse response = cryptoService.hash(new HashRequest("SHA-224", "abc", null));

        assertThat(response.algorithm()).isEqualTo("SHA-224");
        assertThat(response.isHmac()).isFalse();
        assertThat(response.digest())
                .isEqualTo("23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7");
        assertThat(response.digest()).hasSize(56);
    }

    @Test
    @DisplayName("Should treat SHA224 and SHA-224 as the same algorithm and use HmacSHA224 for HMAC")
    void sha224AliasCanonicalises() throws Exception {
        HashResponse canonical = cryptoService.hash(new HashRequest("SHA-224", "abc", null));
        HashResponse aliased = cryptoService.hash(new HashRequest("SHA224", "abc", null));
        HashResponse lowerCased = cryptoService.hash(new HashRequest("sha-224", "abc", null));

        assertThat(aliased.algorithm()).isEqualTo("SHA-224");
        assertThat(aliased.isHmac()).isFalse();
        assertThat(aliased.digest()).isEqualTo(canonical.digest());
        assertThat(lowerCased.digest()).isEqualTo(canonical.digest());

        HashResponse hmac = cryptoService.hash(new HashRequest("SHA224", "abc", "secret-key"));
        assertThat(hmac.algorithm()).isEqualTo("HmacSHA224");
        assertThat(hmac.isHmac()).isTrue();

        Mac reference = Mac.getInstance("HmacSHA224");
        reference.init(new SecretKeySpec("secret-key".getBytes(StandardCharsets.UTF_8), "HmacSHA224"));
        String expected = java.util.HexFormat.of().formatHex(reference.doFinal("abc".getBytes(StandardCharsets.UTF_8)));
        assertThat(hmac.digest()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should reject an unsupported hash algorithm")
    void rejectUnsupportedAlgorithm() {
        assertThatThrownBy(() -> cryptoService.hash(new HashRequest("SHA-512X", "abc", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported hash algorithm: SHA-512X")
                .hasMessageContaining("SHA-384");
        assertThatThrownBy(() -> cryptoService.hash(new HashRequest("SHA3-256", "abc", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported hash algorithm");
    }

    @Test
    @DisplayName("Should percent-encode in COMPONENT, PATH and FORM modes")
    void shouldPercentEncodeInEachMode() {
        String content = "a b/c?d=\u00e9";

        assertThat(cryptoService.url(new UrlRequest("ENCODE", content, "COMPONENT")).result())
                .isEqualTo("a%20b%2Fc%3Fd%3D%C3%A9");
        assertThat(cryptoService.url(new UrlRequest("ENCODE", content, "PATH")).result())
                .isEqualTo("a%20b/c%3Fd%3D%C3%A9");
        assertThat(cryptoService.url(new UrlRequest("ENCODE", content, "FORM")).result())
                .isEqualTo("a+b%2Fc%3Fd%3D%C3%A9");

        for (String mode : List.of("COMPONENT", "PATH", "FORM")) {
            UrlResponse response = cryptoService.url(new UrlRequest("encode", "AZaz09-._~", mode));
            assertThat(response.result()).isEqualTo("AZaz09-._~");
            assertThat(response.action()).isEqualTo("ENCODE");
            assertThat(response.mode()).isEqualTo(mode);
        }

        // INV-2: a literal `+` is never emitted unescaped outside FORM's space spelling,
        // so decode can never confuse it with a form space.
        for (String mode : List.of("COMPONENT", "PATH", "FORM")) {
            assertThat(cryptoService.url(new UrlRequest("ENCODE", "a+b", mode)).result())
                    .isEqualTo("a%2Bb");
        }
    }

    @Test
    @DisplayName("Should default a missing mode to COMPONENT and normalise case")
    void shouldDefaultUrlModeToComponent() {
        UrlResponse response = cryptoService.url(new UrlRequest("encode", "a b", null));

        assertThat(response.mode()).isEqualTo("COMPONENT");
        assertThat(response.result()).isEqualTo("a%20b");

        assertThat(cryptoService.url(new UrlRequest("encode", "a b", "  ")).mode()).isEqualTo("COMPONENT");
        assertThat(cryptoService.url(new UrlRequest("ENCODE", "a b", "path")).result()).isEqualTo("a%20b");
        assertThat(cryptoService.url(new UrlRequest("ENCODE", "a b", "form")).result()).isEqualTo("a+b");
    }

    @Test
    @DisplayName("Should decode each mode back to the original content with mode-specific plus handling")
    void shouldDecodeRoundTripWithModeSpecificPlus() {
        String content = "a b/c?d=\u00e9";

        assertThat(cryptoService.url(new UrlRequest("DECODE", "a%20b%2Fc%3Fd%3D%C3%A9", "COMPONENT")).result())
                .isEqualTo(content);
        assertThat(cryptoService.url(new UrlRequest("DECODE", "a%20b/c%3Fd%3D%C3%A9", "PATH")).result())
                .isEqualTo(content);
        assertThat(cryptoService.url(new UrlRequest("DECODE", "a+b%2Fc%3Fd%3D%C3%A9", "FORM")).result())
                .isEqualTo(content);

        assertThat(cryptoService.url(new UrlRequest("DECODE", "a+b", "FORM")).result()).isEqualTo("a b");
        assertThat(cryptoService.url(new UrlRequest("DECODE", "a+b", "COMPONENT")).result()).isEqualTo("a+b");
        assertThat(cryptoService.url(new UrlRequest("DECODE", "a+b", "PATH")).result()).isEqualTo("a+b");
        for (String mode : List.of("COMPONENT", "PATH", "FORM")) {
            assertThat(cryptoService.url(new UrlRequest("DECODE", "%2B", mode)).result()).isEqualTo("+");
        }
        assertThat(cryptoService.url(new UrlRequest("DECODE", "%2f", "COMPONENT")).result()).isEqualTo("/");
    }

    @Test
    @DisplayName("Should reject truncated or non-hex percent escapes with IllegalArgumentException")
    void shouldRejectMalformedPercentEscape() {
        for (String malformed : List.of("%2", "%ZZ", "abc%4", "abc%g0", "%")) {
            assertThatThrownBy(() -> cryptoService.url(new UrlRequest("DECODE", malformed, "COMPONENT")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid percent-encoding");
        }
    }

    @Test
    @DisplayName("Should reject undecodable UTF-8 byte sequences with IllegalArgumentException")
    void shouldRejectUndecodableUtf8() {
        for (String malformed : List.of("%E9", "%C3", "%FF", "%C0%AF")) {
            assertThatThrownBy(() -> cryptoService.url(new UrlRequest("DECODE", malformed, "COMPONENT")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid UTF-8 sequence");
        }
    }

    @Test
    @DisplayName("Should reject an unknown action or mode with IllegalArgumentException")
    void shouldRejectUnknownUrlActionAndMode() {
        assertThatThrownBy(() -> cryptoService.url(new UrlRequest("SCRAMBLE", "abc", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid action");
        assertThatThrownBy(() -> cryptoService.url(new UrlRequest("ENCODE", "abc", "SEGMENT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid mode");
    }

    @Test
    @DisplayName("Should decompose an absolute URL with userInfo, port, duplicate parameters and fragment")
    void shouldParseUrlComponents() {
        UrlParseResponse response = cryptoService.parseUrl(new UrlParseRequest(
                "https://alice:s3cr3t@example.com:8443/a/b%20c?tag=one&tag=two&flag&empty=&q=a+b#frag"));

        assertThat(response.scheme()).isEqualTo("https");
        assertThat(response.userInfo()).isEqualTo("alice:s3cr3t");
        assertThat(response.host()).isEqualTo("example.com");
        assertThat(response.port()).isEqualTo(8443);
        assertThat(response.path()).isEqualTo("/a/b%20c");
        assertThat(response.query()).isEqualTo("tag=one&tag=two&flag&empty=&q=a+b");
        assertThat(response.fragment()).isEqualTo("frag");
        assertThat(response.parameters()).containsExactly(
                new UrlQueryParameter("tag", "one"),
                new UrlQueryParameter("tag", "two"),
                new UrlQueryParameter("flag", ""),
                new UrlQueryParameter("empty", ""),
                new UrlQueryParameter("q", "a b"));
    }

    @Test
    @DisplayName("Should return null for absent components and an empty parameter list")
    void shouldParseAbsoluteUrlWithoutOptionalComponents() {
        UrlParseResponse response = cryptoService.parseUrl(new UrlParseRequest("https://example.com"));

        assertThat(response.scheme()).isEqualTo("https");
        assertThat(response.userInfo()).isNull();
        assertThat(response.host()).isEqualTo("example.com");
        assertThat(response.port()).isNull();
        assertThat(response.path()).isNull();
        assertThat(response.query()).isNull();
        assertThat(response.fragment()).isNull();
        assertThat(response.parameters()).isEmpty();
    }

    @Test
    @DisplayName("Should parse a relative hierarchical URL")
    void shouldParseRelativeUrl() {
        UrlParseResponse response = cryptoService.parseUrl(new UrlParseRequest("/api/v1/items?limit=10&limit=20"));

        assertThat(response.scheme()).isNull();
        assertThat(response.userInfo()).isNull();
        assertThat(response.host()).isNull();
        assertThat(response.port()).isNull();
        assertThat(response.path()).isEqualTo("/api/v1/items");
        assertThat(response.query()).isEqualTo("limit=10&limit=20");
        assertThat(response.fragment()).isNull();
        assertThat(response.parameters()).containsExactly(
                new UrlQueryParameter("limit", "10"),
                new UrlQueryParameter("limit", "20"));
    }

    @Test
    @DisplayName("Should decode '+' as space and '%2B' as a literal plus")
    void shouldDistinguishPlusFromEncodedPlus() {
        UrlParseResponse response = cryptoService.parseUrl(new UrlParseRequest("/search?a=1+2&b=x%2By&c=%C3%A9"));

        assertThat(response.parameters()).containsExactly(
                new UrlQueryParameter("a", "1 2"),
                new UrlQueryParameter("b", "x+y"),
                new UrlQueryParameter("c", "\u00e9"));
    }

    @Test
    @DisplayName("Should return an empty parameter list for empty and missing queries")
    void shouldParseEmptyAndMissingQuery() {
        UrlParseResponse withEmptyQuery = cryptoService.parseUrl(new UrlParseRequest("/path?"));
        assertThat(withEmptyQuery.query()).isNull();
        assertThat(withEmptyQuery.parameters()).isEmpty();

        UrlParseResponse withSeparatorOnly = cryptoService.parseUrl(new UrlParseRequest("/path?&&"));
        assertThat(withSeparatorOnly.parameters()).isEmpty();

        UrlParseResponse withoutQuery = cryptoService.parseUrl(new UrlParseRequest("/path#frag"));
        assertThat(withoutQuery.query()).isNull();
        assertThat(withoutQuery.fragment()).isEqualTo("frag");
        assertThat(withoutQuery.parameters()).isEmpty();
    }

    @Test
    @DisplayName("Should reject malformed URLs, opaque URIs, illegal ports, bad escapes and invalid UTF-8")
    void shouldRejectMalformedUrlParse() {
        for (String malformed : List.of(
                "https://example.com/a b",
                "https://exa mple.com/",
                "https://example.com:99999999/path",
                "mailto:alice@example.com",
                "urn:isbn:0451450523")) {
            assertThatThrownBy(() -> cryptoService.parseUrl(new UrlParseRequest(malformed)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThatThrownBy(() -> cryptoService.parseUrl(new UrlParseRequest("/path?a=%2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed URL");
        assertThatThrownBy(() -> cryptoService.parseUrl(new UrlParseRequest("/path?a=%ZZ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed URL");
        assertThatThrownBy(() -> cryptoService.parseUrl(new UrlParseRequest("/path/caf%C3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid UTF-8 sequence");
        assertThatThrownBy(() -> cryptoService.parseUrl(new UrlParseRequest("/path?a=%FF")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid UTF-8 sequence");
        assertThatThrownBy(() -> cryptoService.parseUrl(new UrlParseRequest("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url is required");
    }

    @Test
    @DisplayName("Should accept 128 query parameters and reject 129 without truncating")
    void shouldRejectUrlWithTooManyQueryParameters() {
        UrlParseResponse response = cryptoService.parseUrl(new UrlParseRequest(urlWithParameters(128)));

        assertThat(response.parameters()).hasSize(128);
        assertThat(response.parameters().get(0)).isEqualTo(new UrlQueryParameter("p0", "0"));
        assertThat(response.parameters().get(127)).isEqualTo(new UrlQueryParameter("p127", "127"));

        // Empty pairs ("a=1&&b=2", a trailing "&") are not parameters and must not consume the budget.
        assertThat(cryptoService.parseUrl(new UrlParseRequest(urlWithParameters(128) + "&&")).parameters())
                .hasSize(128);

        assertThatThrownBy(() -> cryptoService.parseUrl(new UrlParseRequest(urlWithParameters(129))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 128 query parameters");
    }

    private static String urlWithParameters(int count) {
        StringBuilder url = new StringBuilder("/path?");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                url.append('&');
            }
            url.append('p').append(i).append('=').append(i);
        }
        return url.toString();
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when unsupported algorithm is provided")
    void shouldThrowOnUnsupportedAlgorithm() {
        HashRequest request = new HashRequest("INVALID-ALG", "Hello World", null);
        assertThatThrownBy(() -> cryptoService.hash(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported hash algorithm");
    }

    @Test
    @DisplayName("Should encode and decode Base64 correctly")
    void shouldEncodeAndDecodeBase64() {
        Base64Request encodeReq = new Base64Request("ENCODE", "Hello, UtilTools API!", false);
        Base64Response encodeRes = cryptoService.base64(encodeReq);
        assertThat(encodeRes.result()).isEqualTo("SGVsbG8sIFV0aWxUb29scyBBUEkh");

        Base64Request decodeReq = new Base64Request("DECODE", "SGVsbG8sIFV0aWxUb29scyBBUEkh", false);
        Base64Response decodeRes = cryptoService.base64(decodeReq);
        assertThat(decodeRes.result()).isEqualTo("Hello, UtilTools API!");
    }

    @Test
    @DisplayName("Should generate requested number of valid UUIDs")
    void shouldGenerateUuids() {
        UuidResponse standard = cryptoService.generateUuids(3, false);
        assertThat(standard.count()).isEqualTo(3);
        assertThat(standard.uuids()).hasSize(3);
        assertThat(standard.uuids().getFirst()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

        UuidResponse compact = cryptoService.generateUuids(2, true);
        assertThat(compact.count()).isEqualTo(2);
        assertThat(compact.uuids().getFirst()).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("Should inspect canonical and compact UUIDs and report version, variant and normalised forms")
    void shouldInspectCanonicalAndCompactUuids() {
        UuidInspectResponse canonical = cryptoService.inspectUuid("550e8400-e29b-41d4-a716-446655440000");
        assertThat(canonical.canonical()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(canonical.compact()).isEqualTo("550e8400e29b41d4a716446655440000");
        assertThat(canonical.version()).isEqualTo(4);
        assertThat(canonical.variant()).isEqualTo("RFC_4122");
        assertThat(canonical.unixTimestampMillis()).isNull();

        UuidInspectResponse upperCompact = cryptoService.inspectUuid("  550E8400E29B41D4A716446655440000  ");
        assertThat(upperCompact).isEqualTo(canonical);

        UuidInspectResponse ncs = cryptoService.inspectUuid("ffffffff-0000-1000-0000-000000000000");
        assertThat(ncs.version()).isEqualTo(1);
        assertThat(ncs.variant()).isEqualTo("NCS");
    }

    @Test
    @DisplayName("shouldInspectV7RecoversMillisPrefix")
    void shouldInspectV7RecoversMillisPrefix() {
        CryptoService fixedClockService = new CryptoService(
                Clock.fixed(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS), ZoneOffset.UTC));
        String compactV7 = fixedClockService.generateUuids(1, true, "V7").uuids().getFirst();

        UuidInspectResponse inspect = fixedClockService.inspectUuid(compactV7);
        assertThat(inspect.version()).isEqualTo(7);
        assertThat(inspect.variant()).isEqualTo("RFC_4122");
        assertThat(inspect.unixTimestampMillis()).isEqualTo(FIXED_CLOCK_MILLIS);
        assertThat(inspect.compact()).isEqualTo(compactV7);
        assertThat(inspect.canonical())
                .isEqualTo(compactV7.substring(0, 8) + "-"
                        + compactV7.substring(8, 12) + "-"
                        + compactV7.substring(12, 16) + "-"
                        + compactV7.substring(16, 20) + "-"
                        + compactV7.substring(20));
    }

    @Test
    @DisplayName("Should reject blank, 31-hex, non-hex and misplaced-hyphen UUID inspect inputs")
    void shouldRejectInvalidInspectInputs() {
        assertThatThrownBy(() -> cryptoService.inspectUuid(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value must be a UUID in canonical (8-4-4-4-12) or compact (32-hex) form");
        for (String invalid : List.of("", "   ")) {
            assertThatThrownBy(() -> cryptoService.inspectUuid(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("value must be a UUID in canonical (8-4-4-4-12) or compact (32-hex) form");
        }
        for (String invalid : List.of(
                "550e8400e29b41d4a71644665544000",
                "550e8400e29b41d4a7164466554400001",
                "550e8400e29b41d4a71644665544000g",
                "550e8-400e29b41d4a716446655440000",
                "550e8400-e29b-41d4-a716-44665544000")) {
            assertThatThrownBy(() -> cryptoService.inspectUuid(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("value must be a valid UUID in canonical (8-4-4-4-12) or compact (32-hex) form");
        }
    }

    @Test
    @DisplayName("Should generate UUIDv7 with the 48-bit millisecond prefix and version nibble 7 under a fixed clock")
    void shouldGenerateUuidV7WithFixedClock() {
        CryptoService fixedClockService = new CryptoService(
                Clock.fixed(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS), ZoneOffset.UTC));

        List<String> uuids = fixedClockService.generateUuids(5, false, "V7").uuids();
        assertThat(uuids).hasSize(5);

        for (String uuid : uuids) {
            assertThat(uuid).matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
            assertThat(uuid.substring(0, 8) + uuid.substring(9, 13)).isEqualTo("017f22e279b0");
            assertThat(uuid.charAt(14)).isEqualTo('7');
            assertThat("89ab").contains(String.valueOf(uuid.charAt(19)));
        }
        assertThat(uuids).isSorted();
        assertThat(uuids.getFirst()).isLessThan(uuids.get(1));
    }

    @Test
    @DisplayName("Should generate duplicate-free UUIDv7 values across threads without clock flake")
    void shouldGenerateDuplicateFreeUuidV7UnderConcurrency() throws Exception {
        CryptoService fixedClockService = new CryptoService(
                Clock.fixed(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS), ZoneOffset.UTC));

        int threads = 8;
        int mintsPerThread = 10000;
        Set<String> collected = ConcurrentHashMap.newKeySet();
        List<List<String>> perThread = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            perThread.add(new ArrayList<>(mintsPerThread));
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                List<String> sink = perThread.get(t);
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < mintsPerThread; i++) {
                            String id = fixedClockService.generateUuidV7().toString();
                            collected.add(id);
                            sink.add(id);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        int total = threads * mintsPerThread;
        assertThat(collected).hasSize(total);
        assertThat(perThread).allSatisfy(list -> assertThat(list).hasSize(mintsPerThread));

        for (List<String> list : perThread) {
            for (int i = 1; i < list.size(); i++) {
                String previous = list.get(i - 1);
                String current = list.get(i);
                long currentMillis = timestampMillis(current);
                long previousMillis = timestampMillis(previous);
                assertThat(currentMillis)
                        .as("millis must not regress for %s -> %s", previous, current)
                        .isGreaterThanOrEqualTo(previousMillis);
                if (currentMillis == previousMillis) {
                    assertThat(sequence(current))
                            .as("seq must not regress inside one millisecond for %s -> %s", previous, current)
                            .isGreaterThanOrEqualTo(sequence(previous));
                }
            }
        }
    }

    @Test
    @DisplayName("Should never regress the counter when the clock steps backwards")
    void shouldNeverRegressCounterWhenClockGoesBackwards() {
        MutableClock mutableClock = new MutableClock(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS));
        CryptoService service = new CryptoService(mutableClock);

        String first = service.generateUuidV7().toString();
        mutableClock.set(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS - 60_000L));
        String second = service.generateUuidV7().toString();

        assertThat(timestampMillis(second)).isGreaterThanOrEqualTo(timestampMillis(first));
        assertThat(second).isGreaterThan(first);
        assertThat(second.charAt(14)).isEqualTo('7');
    }

    @Test
    @DisplayName("Should generate UUIDv8 with the millisecond prefix, version nibble 8 and ascending counters")
    void shouldGenerateUuidV8WithFixedClock() {
        CryptoService fixedClockService = new CryptoService(
                Clock.fixed(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS), ZoneOffset.UTC));

        UuidResponse response = fixedClockService.generateUuids(5, false, "V8");
        List<String> uuids = response.uuids();
        assertThat(response.version()).isEqualTo("V8");
        assertThat(uuids).hasSize(5);

        List<Long> counters = new ArrayList<>(uuids.size());
        for (String uuid : uuids) {
            assertThat(uuid).matches(UUID_V8_PATTERN);
            assertThat(uuid).startsWith("017f22e2-79b0-");
            assertThat(timestampMillis(uuid)).isEqualTo(FIXED_CLOCK_MILLIS);
            assertThat(uuid.charAt(14)).isEqualTo('8');
            assertThat(uuid.substring(15, 18)).isEqualTo("000");
            assertThat(uuid.charAt(19)).isEqualTo('8');
            long counter = counterLow16(uuid);
            // The least significant 16 bits are the whole counter: the 48 bits before it stay zero.
            assertThat(uuid.substring(24)).isEqualTo(String.format("%012x", counter));
            counters.add(counter);
        }
        assertThat(counters).isSorted().doesNotHaveDuplicates();
        assertThat(uuids).isSorted();
    }

    @Test
    @DisplayName("Should keep 100 UUIDv8 ids from one frozen millisecond lexicographically sorted and unique")
    void shouldSortUuidV8LexicographicallyWithinOneMillisecond() {
        CryptoService fixedClockService = new CryptoService(
                Clock.fixed(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS), ZoneOffset.UTC));

        List<String> uuids = fixedClockService.generateUuids(100, false, "V8").uuids();
        assertThat(uuids).hasSize(100).doesNotHaveDuplicates().isSorted();
        assertThat(uuids).allSatisfy(uuid -> {
            assertThat(uuid).matches(UUID_V8_PATTERN);
            assertThat(uuid.charAt(14)).isEqualTo('8');
        });
        List<Long> counters = uuids.stream().map(CryptoServiceTest::counterLow16).toList();
        assertThat(counters).isSorted().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Should take the caller-supplied UUIDv8 counter modulo 65536 without touching the shared counter")
    void shouldMaskUuidV8CounterModulo65536() {
        CryptoService fixedClockService = new CryptoService(
                Clock.fixed(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS), ZoneOffset.UTC));

        String masked = fixedClockService.generateUuidV8(0x1_0001L).toString();
        String direct = fixedClockService.generateUuidV8(0x0001L).toString();
        assertThat(masked).isEqualTo(direct);
        assertThat(masked).matches(UUID_V8_PATTERN);
        assertThat(counterLow16(masked)).isEqualTo(0x0001L);

        String saturated = fixedClockService.generateUuidV8(-1L).toString();
        assertThat(saturated).matches(UUID_V8_PATTERN).endsWith("ffff");
        assertThat(counterLow16(saturated)).isEqualTo(0xFFFFL);

        // The explicit-counter overload is pure: the batch mint still starts at sequence 0.
        List<String> batch = fixedClockService.generateUuids(2, false, "V8").uuids();
        assertThat(counterLow16(batch.getFirst())).isZero();
        assertThat(counterLow16(batch.get(1))).isEqualTo(0x0001L);
    }

    @Test
    @DisplayName("Should accept the V8 version case-insensitively and keep V4/V7 unchanged")
    void shouldAcceptUuidV8VersionCaseInsensitively() {
        for (String version : List.of("V8", "v8", " V8 ", "v8\t")) {
            UuidResponse response = cryptoService.generateUuids(1, false, version);
            assertThat(response.version()).isEqualTo("V8");
            assertThat(response.uuids().getFirst()).matches(UUID_V8_PATTERN);
        }
        for (String version : List.of("V4", "v4", " V4")) {
            assertThat(cryptoService.generateUuids(1, false, version).version()).isEqualTo("V4");
        }
        for (String version : List.of("V7", "v7", " V7 ")) {
            assertThat(cryptoService.generateUuids(1, false, version).version()).isEqualTo("V7");
        }

        String compact = cryptoService.generateUuids(1, true, "v8").uuids().getFirst();
        assertThat(compact).matches("^[0-9a-f]{32}$");
        assertThat(compact.charAt(12)).isEqualTo('8');
    }

    @Test
    @DisplayName("Should generate duplicate-free UUIDv8 values across threads without clock flake")
    void shouldGenerateDuplicateFreeUuidV8UnderConcurrency() throws Exception {
        CryptoService fixedClockService = new CryptoService(
                Clock.fixed(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS), ZoneOffset.UTC));

        int threads = 8;
        int mintsPerThread = 10000;
        Set<String> collected = ConcurrentHashMap.newKeySet();
        List<List<String>> perThread = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            perThread.add(new ArrayList<>(mintsPerThread));
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                List<String> sink = perThread.get(t);
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < mintsPerThread; i++) {
                            String id = fixedClockService.generateUuidV8().toString();
                            collected.add(id);
                            sink.add(id);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(collected).hasSize(threads * mintsPerThread);
        for (List<String> list : perThread) {
            assertThat(list).hasSize(mintsPerThread);
            for (int i = 1; i < list.size(); i++) {
                String previous = list.get(i - 1);
                String current = list.get(i);
                assertThat(current)
                        .as("uuidv8 must stay strictly ascending for %s -> %s", previous, current)
                        .isGreaterThan(previous);
            }
        }
        assertThat(collected).allSatisfy(uuid -> assertThat(uuid).matches(UUID_V8_PATTERN));
    }

    @Test
    @DisplayName("Should parse the UUID version case-insensitively and reject unknown versions")
    void shouldParseUuidVersionCaseInsensitively() {
        for (String version : List.of("V7", "v7", " V7 ", "v7\t")) {
            UuidResponse response = cryptoService.generateUuids(1, false, version);
            assertThat(response.version()).isEqualTo("V7");
            assertThat(response.uuids().getFirst()).matches(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }
        for (String version : List.of("V4", "v4", " V4")) {
            UuidResponse response = cryptoService.generateUuids(1, false, version);
            assertThat(response.version()).isEqualTo("V4");
            assertThat(response.uuids().getFirst()).matches(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        }
        for (String version : List.of("V9", "")) {
            assertThatThrownBy(() -> cryptoService.generateUuids(1, false, version))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid version:")
                    .hasMessageContaining("Expected V4, V7 or V8.");
        }
    }

    @Test
    @DisplayName("Should generate UUIDv7 in compact 32-hex format")
    void shouldGenerateUuidV7InCompactFormat() {
        List<String> uuids = cryptoService.generateUuids(3, true, "V7").uuids();
        assertThat(uuids).hasSize(3);
        for (String uuid : uuids) {
            assertThat(uuid).matches("^[0-9a-f]{32}$");
            assertThat(uuid.charAt(12)).isEqualTo('7');
        }
    }

    private static long timestampMillis(String uuid) {
        return Long.parseLong(uuid.substring(0, 8) + uuid.substring(9, 13), 16);
    }

    private static long counterLow16(String uuid) {
        return Long.parseLong(uuid.substring(32), 16);
    }

    private static long sequence(String uuid) {
        long randA = Long.parseLong(uuid.substring(15, 18), 16);
        long counterHighNibbleBits = (Character.digit(uuid.charAt(19), 16) & 0x03) << 2;
        long counterLowNibbleBits = (Character.digit(uuid.charAt(20), 16) >> 2) & 0x03;
        return (randA << 4) | counterHighNibbleBits | counterLowNibbleBits;
    }

    private static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    @DisplayName("base32 - should encode UTF-8 bytes to canonical uppercase padded RFC 4648 output")
    void shouldEncodeBase32() {
        assertThat(cryptoService.base32(new Base32Request("ENCODE", "foobar")).result())
                .isEqualTo("MZXW6YTBOI======");
        assertThat(cryptoService.base32(new Base32Request("encode", "Hello World")).result())
                .isEqualTo("JBSWY3DPEBLW64TMMQ======");
        assertThat(cryptoService.base32(new Base32Request("ENCODE", "a")).result())
                .isEqualTo("ME======");
        assertThat(cryptoService.base32(new Base32Request("ENCODE", "\u00e9")).result())
                .isEqualTo("YOUQ====");
    }

    @Test
    @DisplayName("base32 - should decode padded, unpadded and lowercase input")
    void shouldDecodeBase32Tolerantly() {
        assertThat(cryptoService.base32(new Base32Request("DECODE", "MZXW6YTBOI======")))
                .isEqualTo(new Base32Response("DECODE", "foobar"));
        assertThat(cryptoService.base32(new Base32Request("DECODE", "MZXW6YTBOI")).result())
                .isEqualTo("foobar");
        assertThat(cryptoService.base32(new Base32Request("DECODE", "mzxw6ytboi")).result())
                .isEqualTo("foobar");
        assertThat(cryptoService.base32(new Base32Request("DECODE", "JBSWY3DPEBLW64TMMQ======")).result())
                .isEqualTo("Hello World");
    }

    @Test
    @DisplayName("base32 - should round-trip arbitrary UTF-8 text")
    void shouldRoundTripBase32() {
        for (String sample : List.of("f", "fo", "foo", "foob", "fooba", "foobar", "Gr\u00fc\u00dfe \u2603")) {
            String encoded = cryptoService.base32(new Base32Request("ENCODE", sample)).result();
            assertThat(encoded).matches("^[A-Z2-7]*=*$");
            assertThat(encoded.length() % 8).isZero();
            assertThat(cryptoService.base32(new Base32Request("DECODE", encoded)).result()).isEqualTo(sample);
        }
    }

    @Test
    @DisplayName("base32 - should reject invalid characters, impossible lengths and non-zero trailing bits")
    void shouldRejectInvalidBase32() {
        assertThatThrownBy(() -> cryptoService.base32(new Base32Request("DECODE", "MZXW6YTBOI=====!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected character '!'");
        assertThatThrownBy(() -> cryptoService.base32(new Base32Request("DECODE", "MZXW6YTBO0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected character '0'");
        assertThatThrownBy(() -> cryptoService.base32(new Base32Request("DECODE", "MZXW6YTBO")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid length");
        assertThatThrownBy(() -> cryptoService.base32(new Base32Request("DECODE", "MZXW6YTBOJ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-zero trailing bits");
        assertThatThrownBy(() -> cryptoService.base32(new Base32Request("DECODE", "MZXW6=YTB")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("misplaced padding");
        assertThatThrownBy(() -> cryptoService.base32(new Base32Request("DECODE", "MZXW6YTBOI==")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incorrect padding");
    }

    @Test
    @DisplayName("base32 - should reject an unknown action")
    void shouldRejectUnknownBase32Action() {
        assertThatThrownBy(() -> cryptoService.base32(new Base32Request("ROT13", "foobar")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid action");
    }
    // --- JWT decode (decode-only) -------------------------------------------------------------

    private static final String JWT_HEADER_SEGMENT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
    private static final String JWT_PAYLOAD_SEGMENT =
            "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ";
    private static final String JWT_SIGNATURE_SEGMENT = "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
    private static final String JWT_KNOWN_TOKEN =
            JWT_HEADER_SEGMENT + "." + JWT_PAYLOAD_SEGMENT + "." + JWT_SIGNATURE_SEGMENT;

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String compact(String header, String payload, String signature) {
        return header + "." + payload + "." + signature;
    }

    @Test
    @DisplayName("jwtDecode - should decode the known HS256 example without claiming verification")
    void shouldDecodeKnownHs256Token() {
        JwtDecodeResponse decoded = cryptoService.jwtDecode(new JwtDecodeRequest(JWT_KNOWN_TOKEN));

        assertThat(decoded.algorithm()).isEqualTo("HS256");
        assertThat(decoded.type()).isEqualTo("JWT");
        assertThat(decoded.header().path("alg").asText()).isEqualTo("HS256");
        assertThat(decoded.header().path("typ").asText()).isEqualTo("JWT");
        assertThat(decoded.payload().path("sub").asText()).isEqualTo("1234567890");
        assertThat(decoded.payload().path("name").asText()).isEqualTo("John Doe");
        assertThat(decoded.payload().path("iat").asInt()).isEqualTo(1516239022);
        assertThat(decoded.signaturePresent()).isTrue();
        assertThat(decoded.signatureBytes()).isEqualTo(32);
        assertThat(decoded.verified()).isFalse();
    }

    @Test
    @DisplayName("jwtDecode - should accept unpadded base64url segments")
    void shouldDecodeUnpaddedSegments() {
        String header = base64Url("{\"alg\":\"HS512\",\"kid\":\"k1\"}");
        String payload = base64Url("{\"sub\":\"42\"}");
        String signature = base64Url(new byte[] {1, 2, 3});

        JwtDecodeResponse decoded = cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(header, payload, signature)));

        assertThat(header).doesNotContain("=");
        assertThat(payload).doesNotContain("=");
        assertThat(signature).doesNotContain("=");
        assertThat(decoded.algorithm()).isEqualTo("HS512");
        assertThat(decoded.type()).isNull();
        assertThat(decoded.payload().path("sub").asText()).isEqualTo("42");
        assertThat(decoded.signaturePresent()).isTrue();
        assertThat(decoded.signatureBytes()).isEqualTo(3);
        assertThat(decoded.verified()).isFalse();
    }

    @Test
    @DisplayName("jwtDecode - should report an empty signature segment as absent with zero bytes")
    void shouldReportEmptySignatureAsAbsent() {
        JwtDecodeResponse decoded = cryptoService.jwtDecode(new JwtDecodeRequest(
                compact(base64Url("{\"alg\":\"none\"}"), base64Url("{\"sub\":\"1\"}"), "")));

        assertThat(decoded.algorithm()).isEqualTo("none");
        assertThat(decoded.signaturePresent()).isFalse();
        assertThat(decoded.signatureBytes()).isZero();
        assertThat(decoded.verified()).isFalse();
    }

    @Test
    @DisplayName("jwtDecode - should reject malformed compact tokens with a BAD_REQUEST-worthy message")
    void shouldRejectMalformedCompactTokens() {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"1\"}");
        String signature = base64Url(new byte[] {9, 9, 9});
        String paddedHeader = Base64.getUrlEncoder().encodeToString("{\"alg\":\"HS256X\"}"
                .getBytes(StandardCharsets.UTF_8));
        String paddedPayload = Base64.getUrlEncoder().encodeToString("{\"sub\":\"1\"}"
                .getBytes(StandardCharsets.UTF_8));
        String paddedSignature = Base64.getUrlEncoder().encodeToString(new byte[] {1, 2});
        String invalidUtf8 = base64Url(new byte[] {(byte) 0xC3, (byte) 0x28});

        assertThatThrownBy(() -> cryptoService.jwtDecode(new JwtDecodeRequest(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token is required");
        assertThatThrownBy(() -> cryptoService.jwtDecode(new JwtDecodeRequest("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token is required");
        assertThatThrownBy(() -> cryptoService.jwtDecode(new JwtDecodeRequest("a".repeat(16385))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 16384 characters");
        assertThatThrownBy(() -> cryptoService.jwtDecode(new JwtDecodeRequest("two.segments")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly three dot-separated segments");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(header + "." + payload + "." + signature + ".extra")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly three dot-separated segments");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact("", payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header segment must not be empty");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(header, "", signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload segment must not be empty");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(paddedHeader, payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header segment must not be base64url padded");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(header, paddedPayload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload segment must not be base64url padded");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(header, payload, paddedSignature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature segment must not be base64url padded");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact("abc+def", payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header segment is not valid unpadded base64url");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(header, "abcde", signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload segment is not valid unpadded base64url");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(header, payload, "a/b+c"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature segment is not valid unpadded base64url");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(invalidUtf8, payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header segment is not valid UTF-8");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(header, invalidUtf8, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload segment is not valid UTF-8");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(base64Url("[1,2]"), payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header must be a JSON object");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(header, base64Url("\"just-a-string\""), signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must be a JSON object");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(base64Url("{\"alg\":\"HS256\"} true"), payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header must be a JSON object");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(base64Url("{\"typ\":\"JWT\"}"), payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header.alg is required");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(base64Url("{\"alg\":\"  \"}"), payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header.alg is required");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(base64Url("{\"alg\":256}"), payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header.alg is required");
        assertThatThrownBy(() -> cryptoService.jwtDecode(
                new JwtDecodeRequest(compact(base64Url("{\"alg\":\"HS256\",\"typ\":1}"), payload, signature))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header.typ must be a string");
    }
}
