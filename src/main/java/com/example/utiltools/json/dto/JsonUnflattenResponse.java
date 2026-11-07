package com.example.utiltools.json.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record JsonUnflattenResponse(
        ObjectNode resultJson,
        int keyCount,
        int maxDepth
) {}
