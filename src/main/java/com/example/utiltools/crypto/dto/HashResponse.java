package com.example.utiltools.crypto.dto;

public record HashResponse(
        String algorithm,
        String digest,
        boolean isHmac
) {}
