# 🛠️ Util Tools REST API Server

[![Java](https://img.shields.io/badge/Java-21%20LTS-orange.svg?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4%20(3.4.2)-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Build & Tests](https://img.shields.io/badge/Tests-41%20Passed%20(100%25)-success.svg)](https://junit.org/junit5/)
[![OpenAPI](https://img.shields.io/badge/OpenAPI-3.0%20%2F%20Swagger%20UI-blue.svg?logo=swagger)](http://localhost:8080/swagger-ui/index.html)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A high-performance, lightweight, developer-focused utility REST API server built from scratch with **Java 21** and **Spring Boot 4**.

It packs the **3 most useful daily developer utility tools** into clean, predictable, production-grade HTTP APIs with interactive Swagger/OpenAPI 3 documentation, unified response envelopes, and comprehensive automated test coverage.

---

## 📑 Table of Contents

- [The 3 Core Utility Tools](#-the-3-core-utility-tools)
- [Architecture & Tech Stack](#-architecture--tech-stack)
- [Project Directory Structure](#-project-directory-structure)
- [API Endpoints Reference](#-api-endpoints-reference)
- [Detailed Endpoint Guides & Examples](#-detailed-endpoint-guides--examples)
  - [1. DateTime & Timezone Utility](#1-datetime--timezone-utility)
  - [2. Cryptography & Encoding Utility](#2-cryptography--encoding-utility)
  - [3. JSON & Data Processor Utility](#3-json--data-processor-utility)
  - [4. Text Transformation & Statistics Utility](#4-text-transformation--statistics-utility)
  - [5. Batch Hash Jobs (async)](#5-batch-hash-jobs-async)
- [Rate Limiting](#-rate-limiting)
- [Operation Journal & Replay Verification](#-operation-journal--replay-verification)
- [Unified Response & Error Handling](#-unified-response--error-handling)
- [Interactive API Documentation (Swagger UI)](#-interactive-api-documentation-swagger-ui)
- [Quick Start Guide](#-quick-start-guide)
  - [Prerequisites](#prerequisites)
  - [Run with Maven Wrapper](#run-with-maven-wrapper)
  - [Run with Packaged JAR](#run-with-packaged-jar)
  - [Run with Docker](#run-with-docker)
- [Automated Testing](#-automated-testing)

---

## 🎯 The 3 Core Utility Tools

| Tool | Base Path | Core Capabilities |
| :--- | :--- | :--- |
| **🕒 DateTime & Timezone** | `/api/v1/utils/datetime` | Epoch seconds/millis ⇄ ISO-8601 conversion, IANA timezone shifts, DST-aware duration shift, duration shift with month-end clamping, duration diff calculation, current global time |
| **🔐 Crypto & Encoding** | `/api/v1/utils/crypto` | SHA-256, SHA-512, MD5, SHA-1 digests, HMAC keyed signatures, Base64 & URL-safe Base64 encode/decode, UUID v4 batch generation and time-ordered RFC 9562 UUID v7 generation (`version=V4|V7`) |
| **📄 JSON Data Processor** | `/api/v1/utils/json` | JSON formatting/pretty-printing, minification, syntax validation with line/column error diagnostics, JSONPath query evaluation, atomic RFC 6902-style patch with rollback audit |
| **🔤 Text Transformation** | `/api/v1/utils/text` | Deterministic case/format conversion (`UPPER`, `LOWER`, `TITLE`, `CAMEL`, `SNAKE`, `KEBAB`, `SLUG`) and code-point accurate text statistics (characters, words, lines, sentences) |

---

## 🏗 Architecture & Tech Stack

```mermaid
flowchart TD
    Client["Client / Microservice / Webhook"] -->|HTTP / JSON| Controller["REST Controllers (@RestController)"]
    Controller --> Filter["Validation (@Valid) & GlobalExceptionHandler"]
    Controller --> DTO["Request / Response Records"]
    Controller --> Service["Service Layer (DateTime, Crypto, Json)"]
    Service --> Engines["Engines: java.time, MessageDigest/Mac, Jackson, Jayway JsonPath"]
    Service --> Response["Unified ApiResponse&lt;T&gt; Envelope"]
```

- **Language**: **Java 21 LTS** (Records, Pattern Matching, Modern `java.time`, Modern Stream APIs, `HexFormat`)
- **Framework**: **Spring Boot 4** (`spring-boot-starter-web`, `spring-boot-starter-validation`)
- **JSON Processing**: Jackson (`ObjectMapper`) & Jayway `json-path`
- **Documentation**: OpenAPI 3.0 & Swagger UI (`springdoc-openapi-starter-webmvc-ui`)
- **Testing**: JUnit 5, MockMvc, AssertJ (100% test pass rate across 48 unit and integration tests)

---

## 📂 Project Directory Structure

```text
utiltools_api/
├── pom.xml                                    # Maven dependencies & build config
├── mvnw / mvnw.cmd                            # Embedded Maven Wrapper
├── Dockerfile                                 # Multi-stage production container build
├── docs/
│   └── superpowers/
│       ├── specs/                             # Design specification
│       └── plans/                             # TDD Implementation plan
└── src/
    ├── main/
    │   ├── java/com/example/utiltools/
    │   │   ├── UtiltoolsApiApplication.java   # Spring Boot Application entrypoint
    │   │   ├── common/                        # Shared infrastructure
    │   │   │   ├── ApiResponse.java           # Unified JSON envelope
    │   │   │   ├── ErrorResponse.java         # Standard error model
    │   │   │   └── GlobalExceptionHandler.java# Centralized exception handling
    │   │   ├── config/
    │   │   │   └── OpenApiConfig.java         # Swagger UI & OpenAPI 3 metadata
    │   │   ├── datetime/                      # Tool 1: DateTime & Timezone
    │   │   │   ├── controller/DateTimeController.java
    │   │   │   ├── service/DateTimeService.java
    │   │   │   └── dto/
    │   │   ├── crypto/                        # Tool 2: Cryptography & Encoding
    │   │   │   ├── controller/CryptoController.java
    │   │   │   ├── service/CryptoService.java
    │   │   │   └── dto/
    │   │   ├── json/                          # Tool 3: JSON Data Processor
    │   │   │   ├── controller/JsonController.java
    │   │   │   ├── service/JsonService.java
    │   │   │   ├── exception/JsonPatchRollbackException.java
    │   │   │   └── dto/
    │   │   └── ratelimit/                     # Cross-cutting request budget
    │   │       ├── RateLimitProperties.java   # utiltools.ratelimit.* bindings
    │   │       ├── SlidingWindowRateLimiter.java# Clock-driven, thread-safe limiter
    │   │       ├── RateLimitFilter.java       # Servlet filter -> 429 RATE_LIMITED
    │   │       └── RateLimitConfig.java       # FilterRegistrationBean + scheduler
    │   └── resources/
    │       └── application.properties         # Server & Swagger configuration
    └── test/java/com/example/utiltools/
        ├── UtiltoolsApiApplicationTests.java
        ├── common/GlobalExceptionHandlerTest.java
        ├── datetime/
        │   ├── DateTimeControllerTest.java
        │   └── DateTimeServiceTest.java
        ├── crypto/
        │   ├── CryptoControllerTest.java
        │   └── CryptoServiceTest.java
        ├── json/
        │   ├── JsonControllerTest.java
        │   └── JsonServiceTest.java
        └── ratelimit/
            ├── SlidingWindowRateLimiterTest.java
            ├── RateLimitFilterTest.java
            └── RateLimitConfigTest.java
```

---

## ⚡ API Endpoints Reference

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/utils/datetime/convert` | Convert epoch timestamp or ISO string to target timezone & pattern |
| `POST` | `/api/v1/utils/datetime/diff` | Calculate duration & difference between two datetime points |
| `POST` | `/api/v1/utils/datetime/shift` | Add or subtract a duration in a target IANA timezone (DST- and month-end-aware) |
| `GET` | `/api/v1/utils/datetime/current` | Fetch current time in UTC and target timezone |
| `GET` | `/api/v1/utils/datetime/timezones` | List IANA timezones with current UTC offsets, optionally filtered by `prefix` |
| `POST` | `/api/v1/utils/crypto/hash` | Compute cryptographic digest (SHA-256, SHA-512, MD5) or HMAC |
| `POST` | `/api/v1/utils/crypto/base64` | Encode or decode standard / URL-safe Base64 strings |
| `GET` | `/api/v1/utils/crypto/uuid` | Generate random UUID v4 list (standard or compact 32-hex) |
| `POST` | `/api/v1/utils/json/format` | Pretty-print or minify JSON strings |
| `POST` | `/api/v1/utils/json/validate` | Check JSON syntax validity and report exact syntax error line/col |
| `POST` | `/api/v1/utils/json/query` | Evaluate JSONPath queries and extract matching nodes |
| `POST` | `/api/v1/utils/json/patch` | Apply an ordered RFC 6902-style patch atomically, with reverse-order rollback and audit |
| `POST` | `/api/v1/utils/text/transform` | Apply a deterministic case/format operation (`UPPER`, `LOWER`, `TITLE`, `CAMEL`, `SNAKE`, `KEBAB`, `SLUG`) to a string |
| `POST` | `/api/v1/utils/text/stats` | Count characters, non-whitespace characters, words, lines and sentences (code-point aware) |
| `POST` | `/api/v1/utils/jobs/hash` | Submit a bounded async batch-hash job; `202` with a job id, idempotent via `idempotencyKey` |
| `GET` | `/api/v1/utils/jobs/{jobId}` | Poll job status: `QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` |
| `DELETE` | `/api/v1/utils/jobs/{jobId}` | Cancel a `QUEUED` job (`200`, idempotent); `409` `JOB_CANCELLATION_CONFLICT` once it is `RUNNING`/`COMPLETED`/`FAILED` |
| `GET` | `/api/v1/utils/audit/entries` | List journal entries (newest first) with optional `limit` & `state` filters |
| `GET` | `/api/v1/utils/audit/entries/{id}` | Fetch a single journal entry (redacted request/result) |
| `POST` | `/api/v1/utils/audit/entries/{id}/replay` | Replay a deterministic SUCCEEDED entry and verify the outcome |

---

## 📖 Detailed Endpoint Guides & Examples

### Standard Response Format
Every endpoint returns a unified JSON envelope:
```json
{
  "success": true,
  "message": "Operation successful",
  "data": { ... },
  "requestId": "9f3c1a7e-5b2d-4c8a-9e10-2f6d4b8a1c33",
  "timestamp": "2026-10-31T00:50:38.707903775Z"
}
```

Every response — success **and** error — carries the correlation id of the request in both
the `X-Request-Id` response header and the `requestId` envelope field. Send your own
`X-Request-Id` (8–64 chars of `A-Z a-z 0-9 _ -`) to keep your own trace id, otherwise the
server generates a UUID.

---

### 1. DateTime & Timezone Utility

#### A. Convert Datetime Across Timezones
Supports:
- Epoch milliseconds (e.g., `1730335200000`)
- Epoch seconds (e.g., `1730335200`)
- ISO-8601 strings (e.g., `2026-01-01T15:00:00Z`)
- Optional `inputFormat` request field to force the interpretation of `input` — one of `EPOCH_SECONDS`, `EPOCH_MILLIS`, `ISO_8601` (case-insensitive). Omit it, send `null`, or send a blank string to keep auto-detection (numeric values are treated as epoch millis when the digit count exceeds 11, otherwise as epoch seconds).
- Target IANA timezones (e.g., `Asia/Tokyo`, `America/New_York`, `Europe/London`, `UTC`)
- Optional custom formatting pattern (e.g., `yyyy-MM-dd HH:mm:ss`)

```bash
curl -X POST http://localhost:8080/api/v1/utils/datetime/convert \
  -H "Content-Type: application/json" \
  -d '{
    "input": "1730335200000",
    "targetTimeZone": "Asia/Tokyo",
    "customPattern": "yyyy-MM-dd HH:mm:ss"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "DateTime converted successfully",
  "data": {
    "epochMillis": 1730335200000,
    "epochSeconds": 1730335200,
    "isoUtc": "2024-10-31T00:40:00Z",
    "targetTimeZone": "Asia/Tokyo",
    "formattedTarget": "2024-10-31 09:40:00"
  },
  "timestamp": "2026-10-30T16:50:38.707903775Z"
}
```

**Force the input format (optional `inputFormat`):**

`inputFormat` pins the interpretation of `input` instead of relying on digit-count auto-detection. It is useful when the units are ambiguous — for example `1000000000` is auto-detected as epoch **seconds** (2001-09-09T01:46:40Z), while `"inputFormat": "EPOCH_MILLIS"` reads it as epoch millis (1970-01-12T13:46:40Z). Allowed values (case-insensitive) are `EPOCH_SECONDS`, `EPOCH_MILLIS` and `ISO_8601`.

```bash
# EPOCH_SECONDS: a 10-digit value is read as seconds
curl -X POST http://localhost:8080/api/v1/utils/datetime/convert \
  -H "Content-Type: application/json" \
  -d '{
    "input": "1730335200",
    "inputFormat": "EPOCH_SECONDS",
    "targetTimeZone": "Asia/Tokyo"
  }'

# EPOCH_MILLIS: a 13-digit value (or a pre-1973 value) is read as milliseconds
curl -X POST http://localhost:8080/api/v1/utils/datetime/convert \
  -H "Content-Type: application/json" \
  -d '{
    "input": "1730335200000",
    "inputFormat": "EPOCH_MILLIS",
    "targetTimeZone": "Asia/Tokyo"
  }'

# ISO_8601: numeric heuristics are bypassed entirely
curl -X POST http://localhost:8080/api/v1/utils/datetime/convert \
  -H "Content-Type: application/json" \
  -d '{
    "input": "2024-10-31T09:40:00+09:00",
    "inputFormat": "ISO_8601",
    "targetTimeZone": "Asia/Tokyo"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "DateTime converted successfully",
  "data": {
    "epochMillis": 1730335200000,
    "epochSeconds": 1730335200,
    "isoUtc": "2024-10-31T00:40:00Z",
    "targetTimeZone": "Asia/Tokyo",
    "formattedTarget": "2024-10-31T09:40:00+09:00"
  },
  "timestamp": "2026-10-30T16:50:38.707903775Z"
}
```

If `input` does not match the forced `inputFormat` (an ISO-8601 string sent with `EPOCH_MILLIS`, a numeric value sent with `ISO_8601`, or a value outside the representable range, since the response also reports `epochMillis` and `EPOCH_SECONDS` therefore accepts at most `±9223372036854775`) the endpoint answers HTTP 400 with `error` = `BAD_REQUEST`. An unrecognised `inputFormat` answers HTTP 400 with `error` = `VALIDATION_ERROR` and the allowed values in `details`:

```bash
curl -X POST http://localhost:8080/api/v1/utils/datetime/convert \
  -H "Content-Type: application/json" \
  -d '{
    "input": "1730335200000",
    "inputFormat": "BOGUS"
  }'
```

```json
{
  "success": false,
  "message": "Validation failed",
  "error": "VALIDATION_ERROR",
  "details": [
    "inputFormat must be one of EPOCH_SECONDS, EPOCH_MILLIS, ISO_8601"
  ],
  "timestamp": "2026-10-30T16:50:38.707903775Z"
}
```

#### B. Calculate Difference Between Two Datetimes
Computes days, total hours, total minutes, total seconds, milliseconds, and human-readable text.

```bash
curl -X POST http://localhost:8080/api/v1/utils/datetime/diff \
  -H "Content-Type: application/json" \
  -d '{
    "start": "2026-01-01T00:00:00Z",
    "end": "2026-01-03T12:30:00Z"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "DateTime difference calculated successfully",
  "data": {
    "days": 2,
    "hours": 60,
    "minutes": 3630,
    "seconds": 217800,
    "millis": 217800000,
    "humanReadable": "2 days 12 hours 30 minutes 0 seconds"
  },
  "timestamp": "2026-10-30T16:50:38.765157734Z"
}
```

#### C. Get Current Global Time
```bash
curl "http://localhost:8080/api/v1/utils/datetime/current?timeZone=Asia/Taipei"
```

**Response:**
```json
{
  "success": true,
  "message": "Current time retrieved successfully",
  "data": {
    "epochMillis": 1793379038802,
    "epochSeconds": 1793379038,
    "utc": "2026-10-30T16:50:38.802177453Z",
    "local": "2026-10-31T00:50:38.802177453+08:00",
    "timeZone": "Asia/Taipei"
  },
  "timestamp": "2026-10-30T16:50:38.802324893Z"
}
```


#### D. Shift a Datetime (DST-aware)
Applies a signed `amount` of `unit` (`YEARS`, `MONTHS`, `WEEKS`, `DAYS`, `HOURS`, `MINUTES`, `SECONDS`) to `input`
using calendar arithmetic inside `targetTimeZone`, so month-end clamping and daylight-saving transitions are handled
for you. `amount = 0` is rejected; negative amounts subtract. Optional `customPattern` (e.g. `dd MMM yyyy HH:mm z`)
overrides the default ISO-8601 rendering of `formattedTarget`.

```bash
curl -X POST http://localhost:8080/api/v1/utils/datetime/shift \
  -H "Content-Type: application/json" \
  -d '{
    "input": "2026-01-31T12:00:00Z",
    "targetTimeZone": "UTC",
    "amount": 1,
    "unit": "MONTHS"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "DateTime shifted successfully",
  "data": {
    "epochMillis": 1772280000000,
    "epochSeconds": 1772280000,
    "isoUtc": "2026-02-28T12:00:00Z",
    "targetTimeZone": "UTC",
    "formattedTarget": "2026-02-28T12:00:00Z",
    "appliedAmount": 1,
    "appliedUnit": "MONTHS"
  },
  "timestamp": "2026-10-30T16:50:38.802324893Z"
}
```


January 31 + 1 month clamps to February 28. DST is applied in the target zone, so a spring-forward gap is skipped
rather than silently shifting an hour:

```bash
curl -X POST http://localhost:8080/api/v1/utils/datetime/shift \
  -H "Content-Type: application/json" \
  -d '{
    "input": "2026-03-08T01:30:00-05:00",
    "targetTimeZone": "America/New_York",
    "amount": 1,
    "unit": "HOURS"
  }'
```

```json
{
  "success": true,
  "message": "DateTime shifted successfully",
  "data": {
    "epochMillis": 1772955000000,
    "epochSeconds": 1772955000,
    "isoUtc": "2026-03-08T07:30:00Z",
    "targetTimeZone": "America/New_York",
    "formattedTarget": "2026-03-08T03:30:00-04:00",
    "appliedAmount": 1,
    "appliedUnit": "HOURS"
  },
  "timestamp": "2026-10-30T16:50:38.802324893Z"
}
```

#### E. List Timezones (IANA Catalog)
Discovers valid `timeZone` ids — and their current UTC offsets — before calling `/convert`, `/current`, or `/shift`.

| Query param | Required | Default | Notes |
| :---------- | :------- | :------ | :---- |
| `prefix` | no | *(none)* | Case-insensitive, trimmed prefix filter (e.g. `Asia`, `America/New`) |
| `limit` | no | `50` | Whole number `1`–`200`; out-of-range or non-numeric values return HTTP 400 |

Results are sorted lexicographically and all offsets are computed from a single captured instant.

```bash
curl "http://localhost:8080/api/v1/utils/datetime/timezones?prefix=Asia&limit=3"
```

**Response:**
```json
{
  "success": true,
  "message": "Timezones retrieved successfully",
  "data": {
    "prefix": "Asia",
    "limit": 3,
    "total": 99,
    "timeZones": [
      {
        "timeZone": "Asia/Aden",
        "offset": "+03:00",
        "offsetSeconds": 10800,
        "localDateTime": "2026-10-30T19:50:38+03:00"
      },
      {
        "timeZone": "Asia/Almaty",
        "offset": "+05:00",
        "offsetSeconds": 18000,
        "localDateTime": "2026-10-30T21:50:38+05:00"
      },
      {
        "timeZone": "Asia/Amman",
        "offset": "+03:00",
        "offsetSeconds": 10800,
        "localDateTime": "2026-10-30T19:50:38+03:00"
      }
    ]
  },
  "timestamp": "2026-10-30T16:50:38.802324893Z"
}
```

**Error example** (`limit=201`):
```json
{
  "success": false,
  "message": "Limit must be between 1 and 200, got: 201",
  "error": "BAD_REQUEST",
  "timestamp": "2026-10-30T16:50:38.802324893Z"
}
```

---

### 2. Cryptography & Encoding Utility

#### A. Standard Hash Digest (SHA-256)
Supported algorithms: `SHA-256`, `SHA-512`, `MD5`, `SHA-1` (case-insensitive).

```bash
curl -X POST http://localhost:8080/api/v1/utils/crypto/hash \
  -H "Content-Type: application/json" \
  -d '{
    "algorithm": "SHA-256",
    "content": "Hello World"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "Hash generated successfully",
  "data": {
    "algorithm": "SHA-256",
    "digest": "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e",
    "isHmac": false
  },
  "timestamp": "2026-10-30T16:50:38.834878322Z"
}
```

#### B. HMAC-SHA256 Signature
Include `secretKey` to automatically compute a keyed HMAC signature:

```bash
curl -X POST http://localhost:8080/api/v1/utils/crypto/hash \
  -H "Content-Type: application/json" \
  -d '{
    "algorithm": "SHA-256",
    "content": "Hello World",
    "secretKey": "my-secret"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "Hash generated successfully",
  "data": {
    "algorithm": "HmacSHA256",
    "digest": "820254804a971bc380e5f4d44177fdf729c70386997e8b901b7a83054d4043b9",
    "isHmac": true
  },
  "timestamp": "2026-10-30T16:50:38.870474141Z"
}
```

#### C. Base64 Encode / Decode
```bash
# Encode
curl -X POST http://localhost:8080/api/v1/utils/crypto/base64 \
  -H "Content-Type: application/json" \
  -d '{
    "action": "ENCODE",
    "content": "Hello World",
    "urlSafe": false
  }'

# Decode
curl -X POST http://localhost:8080/api/v1/utils/crypto/base64 \
  -H "Content-Type: application/json" \
  -d '{
    "action": "DECODE",
    "content": "SGVsbG8gV29ybGQ="
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "Base64 operation completed successfully",
  "data": {
    "action": "ENCODE",
    "result": "SGVsbG8gV29ybGQ="
  },
  "timestamp": "2026-10-30T16:50:38.923723150Z"
}
```

#### D. UUID Generator
Query parameters:
- `count` (optional, default `1`, max `100`)
- `compact` (optional, default `false`; when `true`, removes hyphens into 32-hex chars)
- `version` (optional, default `V4`; values `V4|V7`, case-insensitive). `V4` is random; `V7` is
  time-ordered RFC 9562 UUIDv7 — 48-bit unix-millisecond prefix plus a per-process monotonic counter,
  so ids sort lexicographically and stay unique under concurrent generation. Any other value returns
  `400 BAD_REQUEST` with `Invalid version:`.

```bash
# random v4 (default), compact
curl "http://localhost:8080/api/v1/utils/crypto/uuid?count=2&compact=true"

# time-ordered v7
curl "http://localhost:8080/api/v1/utils/crypto/uuid?version=V7&count=2"
```

**Response (v4 default, `compact=true`):**
```json
{
  "success": true,
  "message": "UUIDs generated successfully",
  "data": {
    "count": 2,
    "uuids": [
      "3776d0fbdbf94a999a7b029b588ecb47",
      "cb0151c680c24c63a26d1f5a013ad079"
    ],
    "version": "V4"
  },
  "timestamp": "2026-10-30T16:50:38.954060989Z"
}
```

**Response (`version=V7`):** values are ascending in string order and the 12 leading hex chars are the
unix-millisecond timestamp, the 13th hex char is the version nibble `7`:

```json
{
  "success": true,
  "message": "UUIDs generated successfully",
  "data": {
    "count": 2,
    "uuids": [
      "017f22e2-79b0-7c4a-9a1d-2f5b6c7d8e9f",
      "017f22e2-79b0-7c4b-8b2e-4d6f7a8b9c0d"
    ],
    "version": "V7"
  },
  "timestamp": "2026-10-30T16:50:38.954060989Z"
}
```

---

### 3. JSON & Data Processor Utility

#### A. Format / Pretty-Print or Minify JSON
`indent` is optional: it must be an integer in **1-8**, defaults to **2** spaces per nesting level when omitted, and is ignored when `minify` is `true`. Values outside 1-8 are rejected with HTTP 400 and `error` `VALIDATION_ERROR`.

```bash
curl -X POST http://localhost:8080/api/v1/utils/json/format \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"user\":{\"id\":101,\"name\":\"Alice\"}}",
    "minify": false,
    "indent": 4
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "JSON formatted successfully",
  "data": {
    "formattedJson": "{\n    \"user\" : {\n        \"id\" : 101,\n        \"name\" : \"Alice\"\n    }\n}",
    "sizeBytes": 69
  },
  "timestamp": "2026-10-30T16:50:38.987061888Z"
}
```

#### B. Validate JSON Syntax
Accurately detects structural issues and pinpoint exact line & column errors:

```bash
curl -X POST http://localhost:8080/api/v1/utils/json/validate \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"user\": 123"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "JSON syntax is invalid",
  "data": {
    "valid": false,
    "error": "Unexpected end-of-input: expected close marker for Object",
    "line": 1,
    "column": 13,
    "type": null
  },
  "timestamp": "2026-10-30T16:50:39.009667747Z"
}
```

#### C. Query with JSONPath
```bash
curl -X POST http://localhost:8080/api/v1/utils/json/query \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}",
    "jsonPath": "$.users[*].name"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "JSONPath executed successfully",
  "data": {
    "jsonPath": "$.users[*].name",
    "result": [
      "Alice",
      "Bob"
    ]
  },
  "timestamp": "2026-10-30T16:50:39.127057574Z"
}
```

#### D. Atomic Patch with Rollback Audit
```bash
curl -X POST http://localhost:8080/api/v1/utils/json/patch \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"name\":\"Alice\",\"age\":30,\"tags\":[\"a\",\"b\"],\"temp\":{\"x\":1}}",
    "operations": [
      { "op": "replace", "path": "/name", "value": "Bob" },
      { "op": "add", "path": "/email", "value": "bob@example.com" },
      { "op": "test", "path": "/age", "value": 30 },
      { "op": "move", "from": "/temp/x", "path": "/temp/y" },
      { "op": "copy", "from": "/tags/0", "path": "/tags/-" },
      { "op": "remove", "path": "/tags/1" }
    ]
  }'
```

**Response (200):**

```json
{
  "success": true,
  "message": "JSON Patch applied successfully",
  "data": {
    "resultJson": { "name": "Bob", "age": 30, "email": "bob@example.com", "tags": ["a", "a"], "temp": { "y": 1 } },
    "operationsApplied": 6,
    "operationsRolledBack": 0,
    "dryRun": false,
    "audit": [
      { "index": 0, "op": "replace", "path": "/name", "status": "APPLIED", "beforeValue": "Alice", "afterValue": "Bob" }
    ]
  },
  "timestamp": "2026-10-30T16:50:39.127057574Z"
}
```

`resultJson` and `audit[].beforeValue` / `audit[].afterValue` are abbreviated above; the real audit contains one
entry per operation, with `index` 0..N-1 in the submitted order. The `audit` also echoes `from`, `value`, and `error`.

**Supported operations:** `add`, `remove`, `replace`, `test`, `move`, `copy` over RFC 6901 JSON Pointers
(`~0` -> `~`, `~1` -> `/`, `-` appends to an array). Unknown operations, malformed pointers, missing paths, and
array index bounds violations are rejected.

**Rollback semantics (all-or-nothing):** every operation starts `PENDING` and moves to `APPLIED` on success or
`FAILED` on the first error. When an operation fails, all previously `APPLIED` operations are reverted in reverse
order and marked `ROLLED_BACK`, then the request fails with **HTTP 422** — the caller-supplied JSON is never
mutated and no partially patched document is returned.

```bash
curl -X POST http://localhost:8080/api/v1/utils/json/patch \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"name\":\"Alice\"}",
    "operations": [
      { "op": "replace", "path": "/name", "value": "Bob" },
      { "op": "remove", "path": "/missing" }
    ]
  }'
```

**Response (422):**

```json
{
  "success": false,
  "message": "JSON Patch failed at operation 1 (remove /missing): Missing path: /missing; 1 previously applied operation(s) were rolled back",
  "error": "PATCH_ROLLBACK",
  "details": {
    "failedOperationIndex": 1,
    "failedOperation": "remove",
    "failedPath": "/missing",
    "operationsRolledBack": 1,
    "reason": "Missing path: /missing",
    "audit": [
      { "index": 0, "op": "replace", "path": "/name", "status": "ROLLED_BACK", "beforeValue": "Alice", "afterValue": "Alice" },
      { "index": 1, "op": "remove", "path": "/missing", "status": "FAILED", "error": "Missing path: /missing" }
    ]
  },
  "timestamp": "2026-10-30T16:50:39.127057574Z"
}
```

**Dry run:** send `"dryRun": true` to validate the whole list without returning a document. Successful entries are
marked `VALIDATED`, `resultJson` is `null`, and failures still produce `FAILED` + `ROLLED_BACK` audit entries and a
422 — useful as a pre-flight check over the exact same code path.

```json
{ "json": "{\"name\":\"Alice\"}", "operations": [{ "op": "test", "path": "/name", "value": "Alice" }], "dryRun": true }
```

#### E. Circuit breaker & probe lease recovery

The JSONPath query path runs behind a single `CLOSED` / `OPEN` / `HALF_OPEN` circuit breaker, exposed
read-only by `GET /api/v1/utils/resilience` and driven by hand with `POST /api/v1/utils/resilience/probe`.

```json
{
  "success": true,
  "data": {
    "name": "json-query",
    "state": "HALF_OPEN",
    "consecutiveFailures": 0,
    "consecutiveSuccesses": 0,
    "resetAt": "2026-01-01T00:00:30Z",
    "probeInFlight": true,
    "probeLeaseExpiresAt": "2026-01-01T00:00:40Z",
    "auditCapacity": 1024,
    "audit": []
  },
  "message": "Resilience state retrieved",
  "timestamp": "2026-01-01T00:00:31Z"
}
```

| Field | Meaning |
| :--- | :--- |
| `probeInFlight` | `true` while a `HALF_OPEN` probe claim is held and its lease has not expired |
| `probeLeaseExpiresAt` | Deadline of that claim (`null` when the breaker holds no claim) |

Recovery: while a probe lease is live a concurrent caller is refused with `503` `CIRCUIT_OPEN` and reason
`probe already in flight`. Once the lease expires the claim is superseded — the superseded generation can no
longer change counters, state or the audit trail, so a probe that hung inside the delegate and returns late is
ignored — and the next admitted caller claims a fresh generation. The scheduled reaper records exactly one
`PROBE_LEASE_EXPIRED` audit row per expired generation, so the breaker recovers even with no further traffic.

| Property | Default | Purpose |
| :--- | :--- | :--- |
| `utiltools.resilience.probe-lease-seconds` | `10` | Lifetime of a `HALF_OPEN` probe claim before it may be superseded |
| `utiltools.resilience.probe-reaper-interval-ms` | `5000` | Fixed delay between scheduled sweeps that release expired leases |

> **Scope note:** breaker state, the probe claim and the audit trail are in-process and reset on restart.

---

## 🧾 Operation Journal & Replay Verification

All twelve journalled utility operations — `datetime/convert`, `datetime/diff`, `datetime/current`,
`datetime/shift`, `crypto/hash`, `crypto/base64`, `crypto/uuid`, `json/format`, `json/validate`,
`json/query`, `text/transform`, `text/stats` — are recorded in a **bounded in-memory journal** so a failed
hash, JSON query, datetime conversion or text operation leaves a trace of what actually executed, and a retry
can be verified against the original outcome without ever mutating the original entry.
`GET /api/v1/utils/datetime/timezones` is read-only catalogue metadata and is not journalled.

**Lifecycle:** `PENDING` on entry → `SUCCEEDED` with the response DTO, or `FAILED` with an
`errorCode` (`BAD_REQUEST` / `INTERNAL_SERVER_ERROR`). Only `PENDING → SUCCEEDED` and
`PENDING → FAILED` are legal; late or duplicate transitions throw `IllegalStateException`.

**Bounds:** the journal keeps at most `utiltools.audit.max-entries` entries (default `200`,
oldest evicted first).

**Redaction:** in every response the request view replaces the *values* of the secret fields
`secretKey`, `secret`, `password`, `token` and `authorization` (matched case-insensitively, at any
depth) with `"***"`. Field names are preserved and every other field — including `content`,
`json`, `input` and `timeZone` — is echoed verbatim, so avoid putting secrets in non-secret fields.
The raw request is stored privately for replay and is never serialized by `list`, `find` or
`replay`.

**Oversized payloads:** a request whose JSON exceeds `utiltools.audit.max-replay-bytes` (default
`65536`) is **not retained** — the entry keeps only an `omitted` marker instead of the payload and
is flagged `"replayable": false`, so replaying it answers `409 REPLAY_CONFLICT`.

### A. List journal entries

```bash
curl "http://localhost:8080/api/v1/utils/audit/entries?limit=5&state=FAILED"
```
```json
{
  "success": true,
  "message": "Audit entries retrieved successfully",
  "data": [
    {
      "id": "0f4f1f0e9c8b4d7e",
      "requestId": "corr-crypto5678",
      "operation": "CRYPTO_HASH",
      "state": "FAILED",
      "createdAt": "2026-10-31T00:51:02.114Z",
      "completedAt": "2026-10-31T00:51:02.119Z",
      "request": { "algorithm": "NOPE-1", "content": "Hello World", "secretKey": "***" },
      "result": null,
      "errorCode": "BAD_REQUEST",
      "replayable": true,
      "replay": null
    }
  ],
  "requestId": "corr-crypto5678",
  "timestamp": "2026-10-31T00:51:02.121Z"
}
```

`state` accepts `PENDING`, `SUCCEEDED`, `FAILED` (omit for all); `limit` is clamped to the
configured bound. Any other `state` value or a non-numeric `limit` answers `400 BAD_REQUEST` in the
standard envelope rather than `500`.

### B. Fetch a single entry

```bash
curl "http://localhost:8080/api/v1/utils/audit/entries/0f4f1f0e9c8b4d7e"
```

Unknown ids return `404 NOT_FOUND`.

### C. Replay & verify an entry

```bash
curl -X POST "http://localhost:8080/api/v1/utils/audit/entries/3b7d9f2a1c5e4a60/replay"
```
```json
{
  "success": true,
  "message": "Replay verification completed",
  "data": {
    "id": "3b7d9f2a1c5e4a60",
    "operation": "CRYPTO_HASH",
    "state": "SUCCEEDED",
    "completedAt": "2026-10-31T00:51:02.119Z",
    "result": { "algorithm": "SHA-256", "digest": "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", "isHmac": false },
    "replay": {
      "state": "VERIFIED",
      "replayedAt": "2026-10-31T00:52:10.004Z",
      "detail": "results match"
    }
  },
  "requestId": "corr-audit12345",
  "timestamp": "2026-10-31T00:52:10.007Z"
}
```

Replay re-invokes the original service with the privately stored request, compares the JSON of
the new result against the stored result, and records `VERIFIED` or `MISMATCH`. Only
`SUCCEEDED` **deterministic** entries (all operations except `DATETIME_CURRENT` and
`CRYPTO_UUID`) of replayable size can be replayed. The original `state`, `result`, and
`completedAt` are never rewritten — only the attached `replay` record is added. Any other case
returns `409 REPLAY_CONFLICT`.

**Replay is a single-winner operation.** Before the operation runs, the journal *claims* the entry
under the same lock that owns every journal transition, so only one replay per entry can execute:

```
claim:  (no replay | expired IN_PROGRESS) -> IN_PROGRESS   (writes replay.state, replayedAt, leaseExpiresAt)
result: IN_PROGRESS -> VERIFIED | MISMATCH | FAILED         (by the owning claim only)
```

`IN_PROGRESS -> VERIFIED|MISMATCH|FAILED` is the *only* legal replay transition; a replay record is
never rewritten by anyone except the owner of a live claim. A replay record is `IN_PROGRESS` only
while a claim is live, so a `GET` showing `IN_PROGRESS` means another replay of the same entry is
in flight:

```json
"replay": {
  "state": "IN_PROGRESS",
  "replayedAt": "2026-10-31T00:52:09.988Z",
  "detail": "replay in progress",
  "leaseExpiresAt": "2026-10-31T00:52:39.988Z"
}
```

**Claim conflicts:** a second replay while a claim is live answers `409 REPLAY_CONFLICT` and leaves
the in-flight record untouched. The claim lease is `utiltools.audit.replay-lease-seconds` (default
`30`).

**Failed replays are audited.** If `replayInvoke` throws (for example the stored payload is no
longer accepted by the service), the replay is recorded as `FAILED` with a bounded
`detail` before the claim is released, then `409 REPLAY_CONFLICT` is returned. The entry's
original `state`, `result` and `completedAt` are unchanged, so the entry stays replayable and a
later replay can still produce `VERIFIED`:

```json
"replay": {
  "state": "FAILED",
  "replayedAt": "2026-10-31T00:52:10.004Z",
  "detail": "replay failed: unknown algorithm"
}
```

**Crash recovery via lease expiry:** claims live in the JVM heap only. If the JVM dies (or a replay
never completes) the entry is left `IN_PROGRESS`; once `leaseExpiresAt` has passed a later replay
*claims it over* — the stale owner's write is rejected, a new owner token is issued, and the new
owner's `VERIFIED`/`MISMATCH`/`FAILED` is the outcome that is retained. Expiry is evaluated lazily
at claim time; there is no background sweeper. The owner token itself is stored privately and never
serialized by `list`, `find` or `replay`, so a client can never forge or complete another caller's
claim.

---

### 4. Text Transformation & Statistics Utility

Deterministic, locale-independent (`Locale.ROOT`) text helpers. Both endpoints are pure functions of
their request body, so every call is journalled and replayable.

#### Allowed operations

Word tokenization splits the input on every run of `Character.isLetterOrDigit` code points, so
punctuation and whitespace are separators. With tokenizer output `w1..wn`:

| Operation | Rule | `"Hello, World!"` |
| :--- | :--- | :--- |
| `UPPER` | whole input upper-cased | `HELLO, WORLD!` |
| `LOWER` | whole input lower-cased | `hello, world!` |
| `TITLE` | per token: first code point upper-cased, rest lower-cased; joined with a space | `Hello World` |
| `CAMEL` | `w1` lower-cased; later tokens lower-cased then first code point upper-cased; joined with nothing | `helloWorld` |
| `SNAKE` | tokens lower-cased; joined with `_` | `hello_world` |
| `KEBAB` | tokens lower-cased; joined with `-` | `hello-world` |
| `SLUG` | tokens lower-cased, NFD-normalised with combining marks stripped, emptied tokens dropped; joined with `-` | `hello-world` |

`operation` is case-insensitive on input and echoed back upper-case. Unknown operations answer
`400 BAD_REQUEST` and list the allowed values; blank `text` answers `400 VALIDATION_ERROR`.

#### Transform

```bash
curl -X POST http://localhost:8080/api/v1/utils/text/transform \
  -H 'Content-Type: application/json' \
  -d '{"text": "Café déjà vu!", "operation": "slug"}'
```

```json
{
  "success": true,
  "message": "Text transformed successfully",
  "data": {
    "operation": "SLUG",
    "result": "cafe-deja-vu",
    "changed": true,
    "length": 12
  },
  "timestamp": "2026-01-01T00:00:00Z",
  "requestId": "b1f0c4d2-6d4f-4a1f-8b2c-2f0f3a9d1e77"
}
```

`length` is the **Unicode code-point count of `result`** (an emoji counts once), and `changed` is
`true` when `result` differs from the supplied `text`.

#### Statistics

```bash
curl -X POST http://localhost:8080/api/v1/utils/text/stats \
  -H 'Content-Type: application/json' \
  -d '{"text": "Line one.\nTwo 😀 lines! Bye?"}'
```

```json
{
  "success": true,
  "message": "Text statistics computed successfully",
  "data": {
    "characters": 27,
    "charactersNoWhitespace": 22,
    "words": 5,
    "lines": 2,
    "sentences": 3
  },
  "timestamp": "2026-01-01T00:00:00Z",
  "requestId": "0a5c8f31-2b7e-4c66-9d0b-7f1c3e5a9b44"
}
```

| Field | Definition |
| :--- | :--- |
| `characters` | Unicode code points in `text`, whitespace included (a surrogate pair counts once) |
| `charactersNoWhitespace` | code points where `Character.isWhitespace` is false |
| `words` | number of letter/digit runs (the transform tokenizer) |
| `lines` | newline-separated segments; `\r\n` and `\r` are normalised to `\n`, so a trailing newline yields a final empty segment |
| `sentences` | non-blank segments produced by splitting on runs of `.`, `!` and `?` |

#### Journal & replay

Both endpoints call `AuditedOperations`, so every call is recorded as `TEXT_TRANSFORM` or
`TEXT_STATS` in `GET /api/v1/utils/audit/entries`, with the request (redacted like every other
operation) and the response. Both are deterministic, so
`POST /api/v1/utils/audit/entries/{id}/replay` re-runs the stored request and answers
`replay.state = VERIFIED` without mutating the entry's original `state`, `result` or `completedAt`.
A body larger than `utiltools.audit.max-replay-bytes` is still transformed but stored as
non-replayable, so its replay answers the existing `409 REPLAY_CONFLICT`.

---

### 5. Batch Hash Jobs (async)

Hash up to **1000 items** in the background instead of blocking one HTTP request per item. Submission is
accepted immediately with **202** and a `jobId`; you then poll `GET /api/v1/utils/jobs/{jobId}` until the job
reaches `COMPLETED` or `FAILED`, or withdraw it with `DELETE /api/v1/utils/jobs/{jobId}` while it is still
`QUEUED`. Every item is hashed with the same `CryptoService.hash` used by
`POST /api/v1/utils/crypto/hash`, and results are returned in **input order**.

Jobs live in the JVM heap only: this API is **single-instance and in-memory**, so a restart (or a revert)
discards all jobs and idempotency keys — clients then get `404 JOB_NOT_FOUND` and must resubmit.

#### A. Submit a batch (202 Accepted)

`idempotencyKey` is optional. When present, duplicate **or concurrent** submissions with the same key return the
same `jobId` and cause exactly one execution. A *first* submission always reports `"status": "QUEUED"` (the state
it was accepted in, captured before enqueue); a *duplicate* submission returns the current live snapshot of the
existing job instead, so its `status` may already be `RUNNING`, `COMPLETED` or `FAILED`.

An `idempotencyKey` identifies exactly **one canonical request payload** — the `algorithm` (after trim,
upper-case and `SHA256`/`SHA-256` alias normalisation), the **ordered** `items` list, and the `secretKey` (where
`null` and blank are equivalent). An identical retry therefore returns the same `jobId` with no second execution,
while reusing the key with a **changed** payload is rejected with `409 IDEMPOTENCY_CONFLICT` **instead of**
returning the cached job. The conflict is detected before registration, so the rejected submission creates no job
row, hashes nothing and leaves no idempotency residue; the first job is untouched and keeps running.

```bash
curl -i -X POST http://localhost:8080/api/v1/utils/jobs/hash \
  -H "Content-Type: application/json" \
  -d '{
    "algorithm": "SHA-256",
    "items": ["Hello World", "Second payload", "Third payload"],
    "idempotencyKey": "batch-2026-10-31-001"
  }'
```

**Response — `202 Accepted`:**
```json
{
  "success": true,
  "message": "Batch hash job accepted",
  "data": {
    "jobId": "3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3",
    "idempotencyKey": "batch-2026-10-31-001",
    "status": "QUEUED",
    "itemCount": 3,
    "createdAt": "2026-10-31T19:12:04.418273Z"
  },
  "timestamp": "2026-10-31T19:12:04.418503Z"
}
```

**Response — `409 Conflict` (same key, different payload):**
```json
{
  "success": false,
  "message": "Idempotency key 'batch-2026-10-31-001' was already used with a different request payload",
  "error": "IDEMPOTENCY_CONFLICT",
  "details": {
    "idempotencyKey": "batch-2026-10-31-001",
    "existingJobId": "3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3"
  },
  "timestamp": "2026-10-31T19:13:11.204880Z"
}
```

The payload never echoes `items` or `secretKey`; only the key and the existing job id are returned.

#### B. Poll the job snapshot

```bash
curl http://localhost:8080/api/v1/utils/jobs/3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3
```

**Response — `200 OK`, `COMPLETED` (results in input order):**
```json
{
  "success": true,
  "message": "Job status retrieved",
  "data": {
    "jobId": "3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3",
    "idempotencyKey": "batch-2026-10-31-001",
    "status": "COMPLETED",
    "itemCount": 3,
    "results": [
      { "algorithm": "SHA-256", "digest": "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", "isHmac": false },
      { "algorithm": "SHA-256", "digest": "75dd82daf219f04c6ea282151162b52f9ddd41437aac1b8e6d09133232504797", "isHmac": false },
      { "algorithm": "SHA-256", "digest": "a5e019350fd755cb21a23f61f05f85b4e55aa6acc987436084fed97db11da5ad", "isHmac": false }
    ],
    "createdAt": "2026-10-31T19:12:04.418273Z",
    "startedAt": "2026-10-31T19:12:04.420118Z",
    "completedAt": "2026-10-31T19:12:04.421907Z"
  },
  "timestamp": "2026-10-31T19:12:05.012775Z"
}
```

The state machine is `QUEUED → RUNNING → COMPLETED | FAILED` plus `QUEUED → CANCELLED`; `COMPLETED`,
`FAILED` and `CANCELLED` are terminal. A `FAILED` job carries a non-null `error` and a `completedAt`, and never
blocks later jobs.

#### C. Cancel a queued job (200 OK, idempotent)

`DELETE /api/v1/utils/jobs/{jobId}` withdraws a job that is still `QUEUED`. The job moves `QUEUED → CANCELLED`
exactly once: it is either still waiting behind other work (cancellation wins and **nothing is hashed**) or a
worker has already admitted it, in which case the call reports a conflict instead. `startedAt` and `results`
stay absent, and `completedAt` is set to the instant the cancellation was accepted.

```bash
curl -i -X DELETE http://localhost:8080/api/v1/utils/jobs/3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3
```

**Response — `200 OK`, `CANCELLED`:**
```json
{
  "success": true,
  "message": "Job cancelled",
  "data": {
    "jobId": "3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3",
    "idempotencyKey": "batch-2026-10-31-001",
    "status": "CANCELLED",
    "itemCount": 3,
    "createdAt": "2026-10-31T19:12:04.418273Z",
    "completedAt": "2026-10-31T19:12:04.502114Z"
  },
  "timestamp": "2026-10-31T19:12:04.502381Z"
}
```

Cancellation is idempotent: repeating the same `DELETE` on an already-`CANCELLED` job returns **`200`** with the
identical snapshot (`jobId`, `status`, `completedAt` unchanged) rather than a conflict. A `RUNNING`, `COMPLETED`
or `FAILED` job returns `409 JOB_CANCELLATION_CONFLICT` (see [D](#d-failure-modes)), and an unknown or
already-evicted `jobId` returns `404 JOB_NOT_FOUND`. The cancelled snapshot — and its `idempotencyKey` mapping —
stays pollable for `utiltools.jobs.retention-seconds` and is then evicted like any other terminal job, so a
duplicate submission with the same key observes the `CANCELLED` snapshot until the retention cutoff elapses.

#### D. Failure modes

| Situation | HTTP | `error` | What to do |
| :--- | :--- | :--- | :--- |
| Bounded executor queue is saturated | `429 TOO_MANY_REQUESTS` | `QUEUE_FULL` | Retry with the **same** `idempotencyKey` once capacity frees. The rejected submission is rolled back atomically — its job row **and** its idempotency mapping are both removed, so it leaves no residue and the retry with that key starts a brand-new job |
| Unknown or already-evicted `jobId` | `404 NOT_FOUND` | `JOB_NOT_FOUND` | Resubmit the batch |
| Cancelling a job that is `RUNNING`, `COMPLETED` or `FAILED` | `409 CONFLICT` | `JOB_CANCELLATION_CONFLICT` | Stop cancelling; poll `GET` for the terminal snapshot. A repeat `DELETE` on an already-`CANCELLED` job is `200`, not a conflict |
| Same `idempotencyKey` reused with a different `algorithm`, `items` or `secretKey` | `409 CONFLICT` | `IDEMPOTENCY_CONFLICT` | The first job is untouched and keeps running; the conflicting submission is discarded before registration (no job, no execution, no key residue). Pick a new `idempotencyKey` for a genuinely new payload |
| Unsupported `algorithm` | `202`, then `FAILED` on poll | — (`data.error`) | Fix the algorithm and resubmit with a **new** `idempotencyKey` (or none). Reusing the failed job's key replays its cached `FAILED` snapshot instead of re-executing |
| Blank/empty `items` | `400 BAD_REQUEST` | `VALIDATION_ERROR` | Send a non-empty list of non-blank strings (max 1000) |

#### E. Batch job configuration

```properties
# Batch hash jobs (bounded async execution)
utiltools.jobs.worker-count=2
utiltools.jobs.queue-capacity=100
utiltools.jobs.retention-seconds=300
utiltools.jobs.cleanup-interval-ms=60000
```

| Property | Default | Meaning |
| :--- | :--- | :--- |
| `utiltools.jobs.worker-count` | `2` | Worker threads hashing batches; bounds CPU-wide concurrency |
| `utiltools.jobs.queue-capacity` | `100` | `ArrayBlockingQueue` slots for accepted-but-not-started jobs; full queue ⇒ `429 QUEUE_FULL` (`AbortPolicy`) |
| `utiltools.jobs.retention-seconds` | `300` | How long a `COMPLETED`/`FAILED`/`CANCELLED` job (plus its idempotency key) is kept before eviction |
| `utiltools.jobs.cleanup-interval-ms` | `60000` | Interval of the scheduled eviction pass (`@EnableScheduling`); `QUEUED`/`RUNNING` jobs are never evicted, and `CANCELLED` jobs are evicted on the same retention cutoff as `COMPLETED`/`FAILED` |

#### F. Rate limiting interaction (explicit decision)

All three jobs endpoints live under `/api/v1/utils/**`, so they are **not exempt** from `RateLimitFilter`
(see [Rate Limiting](#-rate-limiting)): every `POST /api/v1/utils/jobs/hash`, every
`GET /api/v1/utils/jobs/{jobId}` poll **and** every `DELETE /api/v1/utils/jobs/{jobId}` cancellation consumes
one slot of the caller's window budget, because polling or cancelling a job is a normal API call. Being jobs,
they are **not** additionally exempted — no entry was added to `RateLimitFilter.shouldNotFilter`. Consequences:

- A caller that submits a batch and then polls aggressively can exhaust its own budget and receive
  `429 RATE_LIMITED` (error `RATE_LIMITED`) *before* the job finishes. This is a client-throttling
  response, not a job failure: the job keeps running and is still retrievable once `Retry-After` elapses.
- The two `429` classes are distinguishable by the `error` field: `RATE_LIMITED` (too many calls —
  honour `Retry-After`) vs `QUEUE_FULL` (executor saturated — retry the **same** `idempotencyKey`).
- Recommended polling: a batch completes in milliseconds to a few seconds on the default
  `worker-count=2`, so a small number of polls is enough — **poll no faster than the default rate-limit
  budget** of `utiltools.ratelimit.requests-per-window=60` per `utiltools.ratelimit.window-ms=60000`
  (≈ 1 request/second). Polling every `250 ms` would exhaust that budget after ~15 s and then return
  exactly the `429 RATE_LIMITED` above, so start at ~1 poll/second and back off from there.
- `Retry-After` is sent **only** on a rejection, never on a successful poll, so honour it after a `429`
  rather than using it as a cadence. Treat `429 RATE_LIMITED` as "slow down", never as "job gone".
- The job itself is unaffected by rate limiting and stays retrievable for `utiltools.jobs.retention-seconds`
  after it becomes terminal — `COMPLETED`, `FAILED` **or** `CANCELLED` (the scheduled eviction pass runs every
  `utiltools.jobs.cleanup-interval-ms`).

---

## 🚦 Rate Limiting

Every `/api/v1/utils/**` request passes through `RateLimitFilter` before Spring MVC. A
`SlidingWindowRateLimiter` keeps a fixed-size per-client ring buffer (`long[requests-per-window]`) of
admitted timestamps and admits a call only while fewer than `requests-per-window` of them fall inside
the trailing `window-ms` window. Admission is atomic per client, so concurrent requests can never
exceed the budget.

Rejected requests receive HTTP `429` with the unified envelope and never reach a controller:

```json
{
  "success": false,
  "message": "Rate limit exceeded",
  "error": "RATE_LIMITED",
  "details": { "retryAfterSeconds": 42 },
  "timestamp": "2026-10-31T00:50:00Z"
}
```

Response headers on every `/api/v1/utils/*` call (allowed or rejected):

| Header | Meaning |
| :--- | :--- |
| `Retry-After` | Seconds until a slot frees (rejections only) |
| `X-RateLimit-Limit` | Configured `requests-per-window` |
| `X-RateLimit-Remaining` | Slots left to this client in the current window |
| `X-RateLimit-Reset` | Seconds until the oldest admitted event leaves the window |

A rejected request consumes no capacity, so a retry when `Retry-After` elapses succeeds. Idle
client buckets are evicted by a `@Scheduled` cleanup pass, and `max-tracked-clients` bounds the map:
the oldest idle bucket is dropped first, and once every tracked bucket is active an unseen client is
rejected with the same `429 RATE_LIMITED` response instead of being inserted.

### Configuration (`application.properties`)

| Property | Default | Purpose |
| :--- | :--- | :--- |
| `utiltools.ratelimit.enabled` | `true` | Master switch; `false` passes everything through with no headers |
| `utiltools.ratelimit.requests-per-window` | `60` | Admitted requests per client per window |
| `utiltools.ratelimit.window-ms` | `60000` | Sliding window length in milliseconds |
| `utiltools.ratelimit.cleanup-interval-ms` | `30000` | Fixed delay between idle-bucket cleanup passes |
| `utiltools.ratelimit.max-tracked-clients` | `10000` | Hard cap on tracked client keys (bounds memory) |
| `utiltools.ratelimit.trust-forwarded-header` | `false` | Use the first well-formed `X-Forwarded-For` entry as the client key |

> **Scope note:** state is in-process only. `N` replicas of this service provide `N ×
> requests-per-window` aggregate capacity, and limits are not shared across instances. Distributed
> limiting (Redis/Bucket4j/gateway) is out of scope. Excluded from limiting: `/actuator`,
> `/v3/api-docs`, `/swagger-ui`.

---

## 🛡 Unified Response & Error Handling

When client input fails validation or business rules, `@RestControllerAdvice` formats a clean, standardized error response:

```json
{
  "success": false,
  "message": "Validation failed",
  "error": "VALIDATION_ERROR",
  "details": [
    "algorithm is required (e.g., SHA-256, SHA-512, MD5)",
    "content to hash is required"
  ],
  "timestamp": "2026-10-31T00:50:00Z"
}
```

| HTTP Status | Error Code | Trigger Condition |
| :--- | :--- | :--- |
| `400 BAD_REQUEST` | `VALIDATION_ERROR` | Missing required fields (`@NotBlank`, `@NotNull`, etc.) |
| `400 BAD_REQUEST` | `BAD_REQUEST` | Invalid timezone ID, unsupported hash algorithm, corrupt Base64 |
| `400 BAD_REQUEST` | `BAD_REQUEST` | Malformed HTTP request body |
| `404 NOT_FOUND` | `JOB_NOT_FOUND` | Unknown or evicted batch job id |
| `404 NOT_FOUND` | `NOT_FOUND` | Unknown audit journal entry id |
| `409 CONFLICT` | `REPLAY_CONFLICT` | Replay of a FAILED, non-deterministic, oversized, or still-PENDING entry, or a replay of an entry whose claim is already `IN_PROGRESS` (live lease) or was lost to a lease takeover |
| `409 CONFLICT` | `IDEMPOTENCY_CONFLICT` | Batch job idempotency key reused with a different request payload |
| `409 CONFLICT` | `JOB_CANCELLATION_CONFLICT` | Cancellation attempted on a batch job that is `RUNNING`, `COMPLETED` or `FAILED` |
| `429 TOO_MANY_REQUESTS` | `QUEUE_FULL` | Batch job queue is saturated; retry after capacity frees |
| `429 TOO_MANY_REQUESTS` | `RATE_LIMITED` | Request budget for `/api/v1/utils/*` exhausted (see [Rate Limiting](#-rate-limiting)) |
| `500 INTERNAL_SERVER_ERROR` | `INTERNAL_SERVER_ERROR` | Unhandled runtime exception |

---

## 📖 Interactive API Documentation (Swagger UI)

Interactive Swagger UI and OpenAPI documentation are enabled out-of-the-box:

- **Swagger UI**: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- **OpenAPI 3 JSON Specification**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

You can explore endpoints, inspect schemas, and test API calls directly inside your web browser.

---

## 🚀 Quick Start Guide

### Prerequisites
- **JDK 21** or later
- **Maven 3.8+** (or use the pre-packaged `./mvnw`)

### Run with Maven Wrapper
```bash
# Clone or navigate to the directory
cd /home/aiteam/sample/utiltools_api

# Run application
./mvnw spring-boot:run
```

### Run with Packaged JAR
```bash
# Package the application
./mvnw clean package

# Run the standalone executable JAR
java -jar target/utiltools-api-0.0.1-SNAPSHOT.jar
```

### Run with Docker
A multi-stage [`Dockerfile`](Dockerfile) is included for container deployments:

```bash
# Build the Docker image
docker build -t utiltools-api:latest .

# Run the container
docker run -d -p 8080:8080 --name utiltools-api utiltools-api:latest
```

---

## 🧪 Automated Testing

The project is built with Test-Driven Development (TDD) using JUnit 5, MockMvc, and AssertJ:

```bash
./mvnw clean test
```

### Test Suite Summary:

- **`DateTimeServiceTest`**: Epoch parsing, timezone shifts, duration math, DST-aware shift arithmetic (month-end clamping, spring-forward, fall-back), boundary checks.
- **`DateTimeControllerTest`**: Full HTTP endpoint testing with MockMvc, including shift validation and error envelopes.
- **`CryptoServiceTest`**: Standard hashing vectors, HMAC calculations, Base64 encode/decode, UUID v4 generation, UUIDv7 fixed-clock vector, `version=V4|V7` parsing, clock-regression monotonicity, and an 8-thread x 10000 duplicate-free concurrency invariant.
- **`CryptoControllerTest`**: HTTP validation and crypto response envelope testing, including `version=V4|V7` selection, compact UUIDv7, and the 400 unified envelope for an unknown `version`.
- **`JsonServiceTest`**: Formatting, minification, Jackson parsing error positions, JSONPath queries, six-op patch success, rollback to byte-identical original, missing paths, malformed pointers, `-` append, dry runs, input-not-mutated.
- **`JsonControllerTest`**: JSON endpoints MockMvc test suite, including the 200 patch envelope, the 422 `PATCH_ROLLBACK` audit payload, and empty-`operations` validation rejection.
- **`TextServiceTest`**: Every operation (`UPPER`, `LOWER`, `TITLE`, `CAMEL`, `SNAKE`, `KEBAB`, `SLUG`), diacritic slugging over NFD, emoji/surrogate-pair and multiline statistics, blank-text constraint violations, unknown-operation rejection, and `changed`/`length` measured in code points.
- **`TextControllerTest`**: Text endpoints MockMvc suite, including the snake-case transform envelope, diacritic slugging, `400 BAD_REQUEST` for an unknown operation, `400 VALIDATION_ERROR` for blank text, and a `TEXT_TRANSFORM` entry listed by `/audit/entries` and replayed as `VERIFIED`.
- **`BatchHashJobServiceTest`**: Concurrent same-key deduplication, key/payload fingerprint conflict detection (changed items, reordered items, changed algorithm or `secretKey`), 8-thread different-payload races, identical-key/identical-payload retries that mint no second execution, queue-saturation rollback and retry, result ordering, worker-failure terminal state, eviction safety, queued cancellation (single transition, no hashing afterwards, idempotent repeat), `RUNNING`/terminal cancellation conflict, and `CANCELLED` eviction only after the retention cutoff.
- **`BatchJobControllerTest`**: Job endpoints MockMvc suite (202, 400, 404 `JOB_NOT_FOUND`, 409 `IDEMPOTENCY_CONFLICT`, 429 `QUEUE_FULL`, `DELETE` 200 `CANCELLED`, 404 `JOB_NOT_FOUND` and 409 `JOB_CANCELLATION_CONFLICT`).
- **`GlobalExceptionHandlerTest`**: Centralized error mapping and constraint validation verification.
- **`SlidingWindowRateLimiterTest`**: Exact-cap concurrent admission, deterministic window rollover on a mutable `Clock`, denials consuming no capacity, per-client isolation, idle eviction, and cap enforcement under many spoofed keys.
- **`RateLimitFilterTest`**: MockMvc proof of the `429 RATE_LIMITED` envelope, `Retry-After`/`X-RateLimit-*` headers, and that a rejected call never invokes the controller.
- **`RateLimitConfigTest`**: Property binding and overrides, filter registration for `/api/v1/utils/*` ahead of Spring MVC, and the `@Scheduled` cleanup wiring.
- **`CircuitBreakerTest`**: CLOSED→OPEN thresholds, forced `probe()` semantics, and the `HALF_OPEN` probe lease — a concurrent caller inside the lease gets `CIRCUIT_OPEN` "probe already in flight" with no audit row, a clock advance past the lease admits a new probe with exactly one `PROBE_LEASE_EXPIRED` row, late success and late failure from the superseded generation change no counter/state and append no row, and `reapExpiredProbe()` is idempotent.
- **`ResilienceControllerTest`**: MockMvc coverage of `GET /api/v1/utils/resilience` (`probeInFlight` / `probeLeaseExpiresAt` in the payload) and of `POST /api/v1/utils/resilience/probe` with the unified envelope.
- **`AuditTrailTest`**: Bounded audit ring buffer ordering and capacity-eviction behaviour.
- **`JsonQueryCircuitOpenTest`**: End-to-end proof that an `OPEN` breaker maps to a `503` `CIRCUIT_OPEN` envelope on `/api/v1/utils/json/query`.
- **`UtiltoolsApiApplicationTests`**: Spring ApplicationContext loading verification.
