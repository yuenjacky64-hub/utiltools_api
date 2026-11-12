package com.example.utiltools.crypto.controller;

import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.crypto.dto.Base32Request;
import com.example.utiltools.crypto.dto.Base32Response;
import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.dto.JwtDecodeRequest;
import com.example.utiltools.crypto.dto.JwtDecodeResponse;
import com.example.utiltools.crypto.dto.UrlRequest;
import com.example.utiltools.crypto.dto.UrlResponse;
import com.example.utiltools.crypto.dto.UuidInspectResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import com.example.utiltools.crypto.service.CryptoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/utils/crypto")
@Tag(name = "Crypto & Encoding Utility", description = "Endpoints for cryptographic hashing, HMAC signatures, Base32 encoding/decoding, Base64 encoding/decoding, URL percent-encoding/decoding, decode-only JWT inspection, and UUID generation")
public class CryptoController {

    private final AuditedOperations auditedOperations;
    private final CryptoService cryptoService;

    public CryptoController(AuditedOperations auditedOperations, CryptoService cryptoService) {
        this.auditedOperations = auditedOperations;
        this.cryptoService = cryptoService;
    }

    @PostMapping("/hash")
    @Operation(summary = "Generate cryptographic hash digest", description = "Computes SHA-256, SHA-512, MD5, SHA-1 or HMAC digests")
    public ApiResponse<HashResponse> hash(@Valid @RequestBody HashRequest request) {
        HashResponse result = auditedOperations.hash(request);
        return ApiResponse.success(result, "Hash generated successfully");
    }

    @PostMapping("/base64")
    @Operation(summary = "Encode or decode Base64 strings", description = "Supports standard Base64 and URL-safe Base64 with UTF-8 encoding")
    public ApiResponse<Base64Response> base64(@Valid @RequestBody Base64Request request) {
        Base64Response result = auditedOperations.base64(request);
        return ApiResponse.success(result, "Base64 operation completed successfully");
    }

    @PostMapping("/base32")
    @Operation(summary = "Encode or decode Base32 strings", description = "Deterministic RFC 4648 Base32 (alphabet A-Z2-7) over UTF-8 bytes; encoding emits uppercase padded output, decoding accepts padded or unpadded input case-insensitively")
    public ApiResponse<Base32Response> base32(@Valid @RequestBody Base32Request request) {
        Base32Response result = auditedOperations.base32(request);
        return ApiResponse.success(result, "Base32 operation completed successfully");
    }

    @PostMapping("/url")
    @Operation(summary = "Percent-encode or decode URL components", description = "Deterministic UTF-8 percent-encoding for COMPONENT (RFC 3986 unreserved bytes, space as %20), PATH (additionally preserves /) and FORM (application/x-www-form-urlencoded, space as +) modes")
    public ApiResponse<UrlResponse> url(@Valid @RequestBody UrlRequest request) {
        UrlResponse result = auditedOperations.url(request);
        return ApiResponse.success(result, "URL operation completed successfully");
    }

    @PostMapping("/jwt/decode")
    @Operation(summary = "Decode a compact JWT without verifying it",
            description = "Strict RFC 7519/7515 compact-token decoder: exactly three dot-separated base64url segments, "
                    + "JSON-object header and payload, header.alg required and header.typ optional. Decode-only: the "
                    + "signature is never verified and is reported only as signaturePresent/signatureBytes, so "
                    + "verified is always false. The raw token and signature value are never echoed.")
    public ApiResponse<JwtDecodeResponse> jwtDecode(@Valid @RequestBody JwtDecodeRequest request) {
        JwtDecodeResponse result = auditedOperations.jwtDecode(request);
        return ApiResponse.success(result, "JWT decoded successfully (signature not verified)");
    }

    @GetMapping("/uuid")
    @Operation(summary = "Generate random UUIDs", description = "Generates single or batch UUIDs with optional compact 32-character format; version selects random UUIDv4 (default), time-ordered RFC 9562 UUIDv7, or time-ordered RFC 9562 UUIDv8 (48-bit unix-millisecond prefix plus a per-process monotonic counter)")
    public ApiResponse<UuidResponse> uuid(
            @RequestParam(value = "count", required = false, defaultValue = "1") int count,
            @RequestParam(value = "compact", required = false, defaultValue = "false") boolean compact,
            @RequestParam(value = "version", required = false, defaultValue = "V4") String version) {
        UuidResponse result = auditedOperations.uuid(count, compact, version);
        return ApiResponse.success(result, "UUIDs generated successfully");
    }

    @GetMapping("/uuid/inspect")
    @Operation(summary = "Inspect an existing UUID", description = "Validates an existing UUID in canonical (8-4-4-4-12) or compact (32-hex) form, case-insensitively, and reports the normalised canonical and compact forms, version (0-15), variant (RFC_4122/NCS/MICROSOFT/FUTURE) and, for version 7, the 48-bit unixTimestampMillis; read-only, not journalled")
    public ApiResponse<UuidInspectResponse> uuidInspect(
            @RequestParam(value = "value", required = false) String value) {
        UuidInspectResponse result = cryptoService.inspectUuid(value);
        return ApiResponse.success(result, "UUID inspected successfully");
    }
}
