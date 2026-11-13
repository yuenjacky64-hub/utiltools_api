package com.example.utiltools.crypto.dto;

import java.util.List;

/**
 * Decomposition of a hierarchical URL. Absent components are {@code null}; {@code query} is the
 * raw (undecoded) query text; {@code parameters} preserves duplicate keys and encounter order.
 */
public record UrlParseResponse(
        String scheme,
        String userInfo,
        String host,
        Integer port,
        String path,
        String query,
        String fragment,
        List<UrlQueryParameter> parameters
) {}
