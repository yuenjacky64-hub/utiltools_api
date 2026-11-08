package com.example.utiltools.json.dto;

import java.util.List;
import java.util.Map;

public record JsonDiffResponse(
        List<String> added,
        List<String> removed,
        Map<String, JsonDiffDetail> changed,
        String summary
) {}
