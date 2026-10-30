package com.example.utiltools.crypto;

import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import com.example.utiltools.crypto.service.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

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
}
