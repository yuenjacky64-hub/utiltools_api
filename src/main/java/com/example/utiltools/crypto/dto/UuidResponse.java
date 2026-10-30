package com.example.utiltools.crypto.dto;

import java.util.List;

public record UuidResponse(
        int count,
        List<String> uuids
) {}
