package com.example.utiltools.crypto;

import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.dto.UrlRequest;
import com.example.utiltools.crypto.dto.UrlResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import com.example.utiltools.crypto.service.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
                    .hasMessageContaining("Expected V4 or V7.");
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
}
