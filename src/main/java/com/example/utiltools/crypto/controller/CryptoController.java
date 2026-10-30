package com.example.utiltools.crypto.controller;

import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
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
@Tag(name = "Crypto & Encoding Utility", description = "Endpoints for cryptographic hashing, HMAC signatures, Base64 encoding/decoding, and UUID generation")
public class CryptoController {

    private final CryptoService cryptoService;

    public CryptoController(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @PostMapping("/hash")
    @Operation(summary = "Generate cryptographic hash digest", description = "Computes SHA-256, SHA-512, MD5, SHA-1 or HMAC digests")
    public ApiResponse<HashResponse> hash(@Valid @RequestBody HashRequest request) {
        HashResponse result = cryptoService.hash(request);
        return ApiResponse.success(result, "Hash generated successfully");
    }

    @PostMapping("/base64")
    @Operation(summary = "Encode or decode Base64 strings", description = "Supports standard Base64 and URL-safe Base64 with UTF-8 encoding")
    public ApiResponse<Base64Response> base64(@Valid @RequestBody Base64Request request) {
        Base64Response result = cryptoService.base64(request);
        return ApiResponse.success(result, "Base64 operation completed successfully");
    }

    @GetMapping("/uuid")
    @Operation(summary = "Generate random UUIDs", description = "Generates single or batch UUIDs with optional compact 32-character format")
    public ApiResponse<UuidResponse> uuid(
            @RequestParam(value = "count", required = false, defaultValue = "1") int count,
            @RequestParam(value = "compact", required = false, defaultValue = "false") boolean compact) {
        UuidResponse result = cryptoService.generateUuids(count, compact);
        return ApiResponse.success(result, "UUIDs generated successfully");
    }
}
