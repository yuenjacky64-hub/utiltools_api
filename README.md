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
- [Configuration Check (`python3 cli.py check`)](#-configuration-check-python3-clipy-check)
- [Maintainer Status (`python3 cli.py status [--json]`)](#-maintainer-status-python3-clipy-status---json)

---

## 🎯 The 3 Core Utility Tools

| Tool | Base Path | Core Capabilities |
| :--- | :--- | :--- |
| **🕒 DateTime & Timezone** | `/api/v1/utils/datetime` | Epoch seconds/millis ⇄ ISO-8601 conversion, IANA timezone shifts, DST-aware duration shift, duration shift with month-end clamping, duration diff calculation, current global time |
| **🔐 Crypto & Encoding** | `/api/v1/utils/crypto` | SHA-256, SHA-512, MD5, SHA-1 digests, HMAC keyed signatures, Base64 & URL-safe Base64 encode/decode, deterministic URL percent-encode/decode (`COMPONENT`, `PATH`, `FORM` modes), decode-only compact JWT inspection, UUID v4 batch generation and time-ordered RFC 9562 UUID v7 / UUID v8 generation (`version=V4|V7|V8`) |
| **📄 JSON Data Processor** | `/api/v1/utils/json` | JSON formatting/pretty-printing, minification, syntax validation with line/column error diagnostics, JSONPath query evaluation, atomic RFC 6902-style patch with rollback audit, and NDJSON / JSON Lines conversion (array <-> line-delimited records) |
| **🔤 Text Transformation** | `/api/v1/utils/text` | Deterministic case/format conversion (`UPPER`, `LOWER`, `TITLE`, `CAMEL`, `SNAKE`, `KEBAB`, `SLUG`, `SENTENCE_CASE`), code-point accurate text statistics (characters, words, lines, sentences) and bounded literal/regex find-and-replace |

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
    │   │       ├── RateLimitCostProperties.java# utiltools.ratelimit.costs.* bindings
    │   │       ├── RouteCostResolver.java     # method + URI -> weighted cost
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
| `POST` | `/api/v1/utils/datetime/duration` | Parse an ISO-8601 duration into components and total seconds, or format signed seconds/nanos back to canonical ISO-8601 text |
| `POST` | `/api/v1/utils/datetime/business-days` | Add or subtract a signed number of business days in a target IANA timezone, skipping weekends and an optional ISO `yyyy-MM-dd` holiday list (DST wall-clock preserving) |
| `GET` | `/api/v1/utils/datetime/current` | Fetch current time in UTC and target timezone |
| `GET` | `/api/v1/utils/datetime/timezones` | List IANA timezones with current UTC offsets, optionally filtered by `prefix` |
| `POST` | `/api/v1/utils/crypto/hash` | Compute cryptographic digest (SHA-256, SHA-512, MD5) or HMAC |
| `POST` | `/api/v1/utils/crypto/base64` | Encode or decode standard / URL-safe Base64 strings |
| `POST` | `/api/v1/utils/crypto/url` | Percent-encode or decode a URL component, path segment list or form value (`COMPONENT`, `PATH`, `FORM`) |
| `POST` | `/api/v1/utils/crypto/jwt/decode` | Decode a compact JWT into `algorithm`, `type`, `header` and `payload` without verifying the signature (`verified` is always `false`) |
| `GET` | `/api/v1/utils/crypto/uuid` | Generate random UUID v4 list (standard or compact 32-hex) |
| `POST` | `/api/v1/utils/json/format` | Pretty-print or minify JSON strings |
| `POST` | `/api/v1/utils/json/validate` | Check JSON syntax validity and report exact syntax error line/col |
| `POST` | `/api/v1/utils/json/query` | Evaluate JSONPath queries and extract matching nodes |
| `POST` | `/api/v1/utils/json/patch` | Apply an ordered RFC 6902-style patch atomically, with reverse-order rollback and audit |
| `POST` | `/api/v1/utils/json/merge-patch` | Apply an RFC 7396 merge-patch document (`null` deletes, objects merge recursively, arrays/scalars replace) as a journalled `JSON_MERGE_PATCH` |
| `POST` | `/api/v1/utils/json/to-ndjson` | Export a JSON array as NDJSON / JSON Lines: one compact value per line, `lineCount` and `sizeBytes`, journalled as `JSON_TO_NDJSON` |
| `POST` | `/api/v1/utils/json/from-ndjson` | Import NDJSON / JSON Lines into a JSON array: blank lines and CRLF tolerated, errors name the 1-based line number, 10000-record bound, journalled as `JSON_FROM_NDJSON` |
| `POST` | `/api/v1/utils/json/csv-to-json` | Import CSV into a JSON array of objects with an optional `delimiter` and optional `headerMapping` header renames; first record supplies the keys, malformed CSV returns 400; journalled as `JSON_CSV_TO_JSON` |
| `POST` | `/api/v1/utils/json/diff` | Compare two JSON documents and report `added`, `removed` and `changed` paths (with old/new values and a pinned `summary`) at any nesting level, journalled as `JSON_DIFF` |
| `POST` | `/api/v1/utils/json/pointer` | Resolve one RFC 6901 JSON Pointer (`/a/b/0`, `~1` = `/`, `~0` = `~`, `""` = whole document) and report the addressed node with its `valueType`, journalled as `JSON_POINTER` |
| `POST` | `/api/v1/utils/text/transform` | Apply a deterministic case/format operation (`UPPER`, `LOWER`, `TITLE`, `CAMEL`, `SNAKE`, `KEBAB`, `SLUG`, `SENTENCE_CASE`) to a string |
| `POST` | `/api/v1/utils/text/stats` | Count characters, non-whitespace characters, words, lines and sentences (code-point aware) |
| `POST` | `/api/v1/utils/text/replace` | Replace every (or, with `all=false`, the first) occurrence of a literal or regex `pattern`, reporting `replacements`, `changed` and code-point `length` |
| `POST` | `/api/v1/utils/text/html-entities` | `ENCODE` a string for safe HTML embedding (`&`, `<`, `>`, `"`, `'`) or `DECODE` the five named entities plus decimal/hex numeric references, reporting `changed` and code-point `length` |
| `POST` | `/api/v1/utils/jobs/hash` | Submit a bounded async batch-hash job; `202` with a job id, idempotent via `idempotencyKey` |
| `GET` | `/api/v1/utils/jobs/{jobId}` | Poll job status: `QUEUED` / `RUNNING` / `CANCELLING` / `COMPLETED` / `FAILED` / `CANCELLED` |
| `DELETE` | `/api/v1/utils/jobs/{jobId}` | Cancel a job: `QUEUED` → `CANCELLED` immediately, `RUNNING` → `CANCELLING` (`200`, idempotent, the worker stops at its next checkpoint); `409` `JOB_CANCELLATION_CONFLICT` for `COMPLETED`/`FAILED` |
| `POST` | `/api/v1/utils/jobs/{jobId}/retry` | Requeue a `FAILED` job (`202`); `409` `JOB_RETRY_CONFLICT` for any other status or once `attempt` reaches `maxAttempts` |
| `POST` | `/api/v1/utils/jobs/{jobId}/heartbeat` | Renew a `RUNNING` job's execution lease (`200`); `404` `JOB_NOT_FOUND`, `409` `JOB_RETRY_CONFLICT` unless `RUNNING`, `400` `BAD_REQUEST` once the lease has expired |
| `GET` | `/api/v1/utils/jobs` | List job snapshots newest first; optional `status` filter and `limit` (default `20`, clamped to `1..100`) |
| `GET` | `/api/v1/utils/audit/entries` | List journal entries (newest first) with optional `limit` & `state` filters |
| `GET` | `/api/v1/utils/audit/entries/{id}` | Fetch a single journal entry (redacted request/result) |
| `POST` | `/api/v1/utils/audit/entries/{id}/replay` | Replay a deterministic SUCCEEDED entry and verify the outcome |
| `POST` | `/api/v1/utils/audit/verify` | Sweep-verify up to `limit` (default `20`, clamped to `max-entries`) SUCCEEDED entries in one pass and report per-entry `VERIFIED` / `MISMATCH` / `FAILED` / `SKIPPED` |
| `GET` | `/api/v1/utils/audit/reaps` | List reaper tombstones (newest first) with optional `limit` (default `50`, clamped to `max-entries`) and `reason` filter; read-only |

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

#### F. Parse / Format ISO-8601 Durations
Turns an ISO-8601 duration into components plus total seconds (`PARSE`), or turns signed seconds/nanos back into
canonical ISO-8601 text (`FORMAT`). Parsing accepts an optional leading `+`/`-` and the week, day, hour, minute and
second designators, including fractional seconds (`P2W`, `P1DT2H30M`, `PT1.5S`, `PT0S`); weeks are expanded into
days. Calendar-dependent units (`P1Y`, `P1M`), a bare `P`, and malformed or overlong input are rejected with HTTP 400.
Every call is journalled as `DATETIME_DURATION` and replays deterministically.

```bash
curl -X POST http://localhost:8080/api/v1/utils/datetime/duration \
  -H "Content-Type: application/json" \
  -d '{
    "action": "PARSE",
    "input": "P1DT2H30M"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "DateTime duration processed successfully",
  "data": {
    "action": "PARSE",
    "input": "P1DT2H30M",
    "iso": "P1DT2H30M",
    "negative": false,
    "days": 1,
    "hours": 2,
    "minutes": 30,
    "seconds": 0,
    "nanos": 0,
    "totalSeconds": 95400
  },
  "timestamp": "2026-10-30T16:50:38.802324893Z"
}
```

`FORMAT` goes the other way and keeps the sign, so `{"action": "FORMAT", "seconds": -5400}` answers
`"iso": "-PT1H30M"`, while `{"action": "FORMAT", "seconds": 1, "nanos": 500000000}` answers `"iso": "PT1.5S"`.

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
- `version` (optional, default `V4`; values `V4|V7|V8`, case-insensitive). `V4` is random; `V7` is
  time-ordered RFC 9562 UUIDv7 — 48-bit unix-millisecond prefix plus a per-process monotonic counter,
  so ids sort lexicographically and stay unique under concurrent generation. `V8` is a time-ordered
  RFC 9562 UUIDv8 with the same 48-bit unix-millisecond prefix and version nibble `8`, and the last 16
  bits are the shared per-process monotonic counter — so V8 ids also sort lexicographically within the
  same millisecond. V8 carries no entropy and must not be used as a secret or capability token. Any
  other value returns `400 BAD_REQUEST` with `Invalid version:`.

```bash
# random v4 (default), compact
curl "http://localhost:8080/api/v1/utils/crypto/uuid?count=2&compact=true"

# time-ordered v7
curl "http://localhost:8080/api/v1/utils/crypto/uuid?version=V7&count=2"

# time-ordered v8
curl "http://localhost:8080/api/v1/utils/crypto/uuid?version=V8&count=2"
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

**Response (`version=V8`):** values are ascending in string order, the 12 leading hex chars are the
unix-millisecond timestamp, the 13th hex char is the version nibble `8` and the last 16 bits are the
per-process monotonic counter:

```json
{
  "success": true,
  "message": "UUIDs generated successfully",
  "data": {
    "count": 2,
    "uuids": [
      "017f22e2-79b0-8000-8000-000000000001",
      "017f22e2-79b0-8000-8000-000000000002"
    ],
    "version": "V8"
  },
  "timestamp": "2026-10-30T16:50:38.954060989Z"
}
```

#### E. URL Percent-Encode / Decode
`POST /api/v1/utils/crypto/url` is the deterministic, journalled counterpart of `base64` for URL text:
same `ApiResponse` envelope, same validation, same replay guarantees, no client-side library needed.

| Field | Required | Values |
| :--- | :--- | :--- |
| `action` | yes | `ENCODE` \| `DECODE` (case-insensitive) |
| `content` | yes | non-blank text |
| `mode` | no, default `COMPONENT` | `COMPONENT` \| `PATH` \| `FORM` (case-insensitive) |

Only UTF-8 is supported, and the transformation is pure: byte-for-byte percent-encoding with uppercase hex,
nothing locale- or clock-dependent.

| Mode | Preserved characters | Space | Notes |
| :--- | :--- | :--- | :--- |
| `COMPONENT` | `A-Z a-z 0-9 - . _ ~` (RFC 3986 unreserved) | `%20` | strictest; `/`, `?`, `&`, `=` and `+` are escaped |
| `PATH` | `A-Z a-z 0-9 - . _ ~` and `/` | `%20` | for path segments kept as a path |
| `FORM` | `A-Z a-z 0-9 - . _ ~` | `+` | `application/x-www-form-urlencoded` |

```bash
# ENCODE a query component
curl -X POST http://localhost:8080/api/v1/utils/crypto/url \
  -H "Content-Type: application/json" \
  -d '{
    "action": "ENCODE",
    "content": "a b/c?d=é",
    "mode": "COMPONENT"
  }'

# DECODE a form value (+ means space)
curl -X POST http://localhost:8080/api/v1/utils/crypto/url \
  -H "Content-Type: application/json" \
  -d '{
    "action": "DECODE",
    "content": "a+b%2Fc%3Fd%3D%C3%A9",
    "mode": "FORM"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "URL operation completed successfully",
  "data": {
    "action": "ENCODE",
    "mode": "COMPONENT",
    "result": "a%20b%2Fc%3Fd%3D%C3%A9"
  },
  "timestamp": "2026-10-30T16:50:38.954060989Z"
}
```

The same input encodes as `a%20b/c%3Fd%3D%C3%A9` in `PATH` and `a+b%2Fc%3Fd%3D%C3%A9` in `FORM`.

**Decoding rules:** `+` becomes a space **only** in `FORM`; in `COMPONENT` and `PATH` it stays a literal `+`,
while `%2B` decodes to `+` in every mode. A truncated (`%2`) or non-hex (`%ZZ`) escape, and a percent-escape
sequence that is not valid UTF-8 (for example `%E9` or the overlong `%C0%AF`), are rejected with HTTP `400` and
error `BAD_REQUEST` — they never decode to a replacement character. An unknown `action` or `mode` fails bean
validation first and returns HTTP `400` with error `VALIDATION_ERROR`.

**Journal & replay:** each call is recorded as a `CRYPTO_URL` entry that moves `PENDING` →
`SUCCEEDED`/`FAILED`, exactly like `crypto/base64`. Because `action`, `mode` and `result` are pure functions of the
request, the entry is replayable: `POST /api/v1/utils/audit/entries/{id}/replay` re-runs the same encoding and
answers `replay.state = VERIFIED` without rewriting the entry's original `state`, `result` or `completedAt`, and
without appending a second journal entry.

#### F. JWT Decode (decode-only)
`POST /api/v1/utils/crypto/jwt/decode` turns a compact JWS/JWT into its header and claims, so you can inspect
a token while debugging without pulling a JWT library into the client. It is **decode-only**: the signature is
never verified, no key is ever supplied, and `verified` is always `false`. Never use the decoded claims for an
authorization decision without verifying the signature elsewhere.

| Field | Required | Constraints |
| :--- | :--- | :--- |
| `token` | yes | non-blank compact token of at most 16384 characters |

Strictness — anything else is HTTP `400` with error `BAD_REQUEST`:

| Rule | Rejected example |
| :--- | :--- |
| exactly three dot-separated segments | `header.payload` or `header.payload.signature.extra` |
| non-empty header and payload segments | `.payload.signature` |
| unpadded base64url in every segment (`-`/`_` alphabet, no `=` padding, no `+` or `/`) | `eyJhbGciOiJIUzI1NiJ9=` |
| strict UTF-8 decoding of the header and payload bytes | byte sequence `C3 28` |
| header and payload parse as JSON **objects**, with no trailing content | `[1,2]` or `"text"` |
| `header.alg` present and a non-blank string; `header.typ` a string when present | `{"typ":"JWT"}` or `{"alg":256}` |

An empty third segment (`header.payload.`) is legal and reports `signaturePresent: false` with
`signatureBytes: 0` — that is the `alg: none` shape, not an error.

```bash
curl -X POST http://localhost:8080/api/v1/utils/crypto/jwt/decode \
  -H "Content-Type: application/json" \
  -d '{
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "JWT decoded successfully (signature not verified)",
  "data": {
    "algorithm": "HS256",
    "type": "JWT",
    "header": { "alg": "HS256", "typ": "JWT" },
    "payload": { "sub": "1234567890", "name": "John Doe", "iat": 1516239022 },
    "signaturePresent": true,
    "signatureBytes": 32,
    "verified": false
  },
  "timestamp": "2026-10-30T16:50:38.954060989Z"
}
```

`header` and `payload` are returned as raw JSON trees, so claims keep their JSON types (`iat` stays a number).
The response never echoes the raw token or the raw signature segment, and the journal redacts `token` as `"***"`.

**Journal & replay:** each call is recorded as a `CRYPTO_JWT_DECODE` entry that moves `PENDING` →
`SUCCEEDED`/`FAILED`; the entry is replayable and `POST /api/v1/utils/audit/entries/{id}/replay` re-runs the
decode and answers `replay.state = VERIFIED` without appending a second journal entry.

---

### 3. JSON & Data Processor Utility

Covers formatting and minification, syntax validation, JSONPath querying, atomic RFC 6902-style patching,
declarative RFC 7396 merge-patch, and NDJSON / JSON Lines conversion (array <-> line-delimited records).

#### A. Format / Pretty-Print or Minify JSON
`indent` is optional: it must be an integer in **1-8**, defaults to **2** spaces per nesting level when omitted, and is ignored when `minify` is `true`. Values outside 1-8 are rejected with HTTP 400 and `error` `VALIDATION_ERROR`.

`sortKeys` is optional and defaults to `false`. When `true`, every object's field names are recursively ordered in ascending `String.compareTo` order before serialisation, including nested objects and objects inside arrays. Array element order and all scalar values are preserved, and sorting applies to both pretty-printed and minified output, so `indent` still controls the layout. This is useful for diffing, snapshot tests and stable cache keys.

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

**Sorted output (`sortKeys: true`)** — `{"z":1,"a":{"d":4,"b":2},"list":[{"y":1,"x":2}]}` minifies to `{"a":{"b":2,"d":4},"list":[{"x":2,"y":1}],"z":1}`:
```bash
curl -X POST http://localhost:8080/api/v1/utils/json/format \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"z\":1,\"a\":{\"d\":4,\"b\":2},\"list\":[{\"y\":1,\"x\":2}]}",
    "minify": true,
    "sortKeys": true
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "JSON formatted successfully",
  "data": {
    "formattedJson": "{\"a\":{\"b\":2,\"d\":4},\"list\":[{\"x\":2,\"y\":1}],\"z\":1}",
    "sizeBytes": 45
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

**Operation-count limit:** a single request may carry at most **100** operations. The cap is checked by bean
validation on the request body *before* any patching runs, so an oversized list is rejected with **HTTP 400**,
`error` `VALIDATION_ERROR`, and `details` containing `operations must contain at most 100 entries` — no
`resultJson` and no patch audit are produced. The same limit applies to `"dryRun": true`; an empty list still fails
with `operations list is required`.

Oversized request (101 operations, list abbreviated):

```bash
curl -X POST http://localhost:8080/api/v1/utils/json/patch \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"name\":\"Alice\"}",
    "operations": [
      { "op": "add", "path": "/k0", "value": 0 },
      { "op": "add", "path": "/k1", "value": 1 },
      "... 98 more ...",
      { "op": "add", "path": "/k100", "value": 100 }
    ]
  }'
```

**Response (400):**

```json
{
  "success": false,
  "message": "Validation failed",
  "error": "VALIDATION_ERROR",
  "details": [
    "operations must contain at most 100 entries"
  ],
  "timestamp": "2026-10-30T16:50:39.127057574Z"
}
```

#### E. Declarative Merge Patch (RFC 7396)

`POST /api/v1/utils/json/merge-patch` applies a *merge-patch document* instead of an ordered operation list:
a `null` member removes that key, objects merge recursively, and arrays/scalars replace the target value
wholesale. A non-object patch (`7`, `[1,2]`, `"x"`, `null`) replaces the whole target. The journalled operation
is `json/merge-patch` (`JSON_MERGE_PATCH`), so the call appears in the audit journal and replays like the other
JSON utilities.

```bash
curl -X POST http://localhost:8080/api/v1/utils/json/merge-patch \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"a\":1,\"b\":{\"c\":2,\"d\":3}}",
    "patch": "{\"b\":{\"c\":null,\"e\":4},\"f\":[1,2]}"
  }'
```

**Response (200):**

```json
{
  "success": true,
  "message": "JSON merge patch applied successfully",
  "data": {
    "resultJson": { "a": 1, "b": { "d": 3, "e": 4 }, "f": [1, 2] },
    "sizeBytes": 35,
    "changed": true,
    "removedKeys": 1
  },
  "timestamp": "2026-10-30T16:50:39.127057574Z"
}
```

`removedKeys` counts the members deleted by a `null` in the patch, `sizeBytes` is the UTF-8 byte length of the
compact `resultJson`, and `changed` is `false` when the patch leaves the document byte-identical. Both inputs are
parsed and never mutated, so the same request is replay-stable.

**Errors:** malformed `json` or `patch` returns **HTTP 400** `BAD_REQUEST` (`Invalid JSON content ...`); blank
fields return **HTTP 400** `VALIDATION_ERROR`; a patch nested deeper than 64 object levels is rejected with
**HTTP 400** instead of overflowing the stack.

#### F. Structural Diff (added / removed / changed)

`POST /api/v1/utils/json/diff` compares two documents and reports what differs. `jsonA` is the *old* side and
`jsonB` the *new* side; both are strings holding JSON. The journalled operation is `json/diff` (`JSON_DIFF`), so
the call appears in the audit journal with `SUCCEEDED` state and replays as `VERIFIED`.

```bash
curl -X POST http://localhost:8080/api/v1/utils/json/diff \
  -H "Content-Type: application/json" \
  -d '{
    "jsonA": "{\"a\":1,\"b\":{\"c\":2,\"e\":{\"x\":1}},\"g\":\"keep\"}",
    "jsonB": "{\"a\":1,\"b\":{\"c\":5},\"f\":true}"
  }'
```

**Response (200):**

```json
{
  "success": true,
  "message": "JSON documents diffed successfully",
  "data": {
    "added": ["f"],
    "removed": ["b.e", "g"],
    "changed": {
      "b.c":       { "path": "b.c",       "oldValue": "2",       "newValue": "5",      "changeType": "CHANGED" },
      "b.e":       { "path": "b.e",       "oldValue": "{\"x\":1}", "newValue": null,     "changeType": "REMOVED" },
      "g":         { "path": "g",         "oldValue": "\"keep\"", "newValue": null,     "changeType": "REMOVED" },
      "f":         { "path": "f",         "oldValue": null,        "newValue": "true",   "changeType": "ADDED" }
    },
    "summary": "1 added, 2 removed, 1 changed"
  },
  "timestamp": "2026-10-30T16:50:39.127057574Z"
}
```

Objects are compared member by member in first-seen order (member order alone is not a change), arrays position by
position, so out-of-range elements are reported at `path[i]`; a scalar or type mismatch is a single `CHANGED` detail
carrying both compact JSON values, and a whole-root mismatch is reported at the sentinel path `$`. Every change -
added, removed and changed - also appears in `changed`, keyed by its rendered path, which is why `JsonDiffDetail`
carries `changeType` (`ADDED`, `REMOVED`, `CHANGED`). `oldValue`/`newValue` are compact JSON text and the missing side
is `null`. `summary` reads `<n> added, <n> removed, <n> changed`, or `No differences found` when the documents are
byte-equal after parsing. A member name containing `.`, `[`, `]`, `'`, `\` or `$` renders as `['a.b']` (with `\` and
`'` escaped), so distinct structural paths can never collide in `changed`. Nothing is mutated, and the ordering is
deterministic, so the same request is replay-stable.

**Errors:** malformed `jsonA` or `jsonB` returns **HTTP 400** `BAD_REQUEST` naming that field
(`Invalid JSON content in jsonA: ...`); blank fields return **HTTP 400** `VALIDATION_ERROR` (`jsonA content is
required` / `jsonB content is required`); a document nested deeper than 64 object levels is rejected with
`jsonA depth must not exceed 64`, and more than 10000 changes is rejected as well - both **HTTP 400**.

#### G. Resolve an RFC 6901 JSON Pointer

`POST /api/v1/utils/json/pointer` dereferences a single JSON Pointer against a document string. The pointer grammar
is the RFC 6901 one that `json/patch` already uses: tokens are split on `/`, `~1` decodes to `/` **before** `~0` to
`~`, and the empty pointer `""` addresses the whole document. The journalled operation is `json/pointer`
(`JSON_POINTER`), so the call appears in the audit journal with `SUCCEEDED` state and replays as `VERIFIED`.

```bash
curl -X POST http://localhost:8080/api/v1/utils/json/pointer \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"a/b\":{\"~key\":[1,2,3]}}",
    "pointer": "/a~1b/~0key/1"
  }'
```

**Response (200):**

```json
{
  "success": true,
  "message": "JSON pointer resolved successfully",
  "data": {
    "pointer": "/a~1b/~0key/1",
    "found": true,
    "value": 2,
    "valueType": "NUMBER"
  },
  "timestamp": "2026-11-09T05:20:11.412885Z",
  "requestId": "5d0f1c8a-4b12-4c6d-9a37-1f2b0f6f1c33"
}
```

`valueType` is one of `OBJECT`, `ARRAY`, `STRING`, `NUMBER`, `BOOLEAN`, `NULL` - the same naming as `json/validate`.
`pointer` is echoed back verbatim, including `""` for a whole-document lookup. When the addressed member or in-range
index does not exist the call still succeeds with `found: false`, `value: null` and `valueType: null`.

**Errors:** `json` that is not parseable returns **HTTP 400** `BAD_REQUEST` (`Invalid JSON content ...`); a blank
`json` or a missing `pointer` returns **HTTP 400** `VALIDATION_ERROR`; a pointer that does not start with `/`, that
carries a malformed escape (`~2`, trailing `~`), or whose array token is not an RFC 6901 index (`abc`, `-`, `01`)
returns **HTTP 400** `BAD_REQUEST` (`Malformed JSON Pointer ...` / `Invalid array index ...`). The lookup is bounded:
a pointer longer than 1024 characters is rejected with `JSON pointer must not exceed 1024 characters`, more than 64
segments with `JSON pointer must not exceed 64 segments`, and walking *through* a scalar (`/s/x` where `/s` is a
string) is a **HTTP 400** `Cannot traverse through a scalar value at '/s'` rather than a misleading `found: false`.

#### H. CSV to JSON (header-driven import)

`POST /api/v1/utils/json/csv-to-json` imports CSV text into a JSON array of objects. `csvContent` is the CSV text,
`delimiter` is optional and defaults to comma (a single character, `\t` or a literal tab for TSV), and
`headerMapping` optionally renames a header to a different JSON property name. The first record supplies the object
keys (blank header names become `columnN`), values stay strings, and the journalled operation is `JSON_CSV_TO_JSON`.

```bash
curl -X POST http://localhost:8080/api/v1/utils/json/csv-to-json \
  -H "Content-Type: application/json" \
  -d '{"csvContent":"name,age\nAlice,30\nBob,25"}'
```

**Response (200):**

```json
{
  "success": true,
  "message": "CSV converted to JSON successfully",
  "data": {
    "resultJson": [
      { "name": "Alice", "age": "30" },
      { "name": "Bob",   "age": "25" }
    ],
    "rowCount": 2,
    "columnCount": 2,
    "headers": ["name", "age"]
  },
  "timestamp": "2026-11-08T22:51:42.003114Z"
}
```

Quoted fields may carry the delimiter, LF, CRLF or CR, doubled quotes and a UTF-8 BOM are tolerated, and a short row
is padded with empty cells. `headerMapping` must reference existing headers, its values must be non-blank, and no two
columns may end up sharing a key.

**Errors:** an unterminated quoted field, text after a closing quote, duplicate header names, an unresolvable
`headerMapping` key or value, a row wider than the header, and more than 10000 rows or columns (or 1000000 cells)
all return **HTTP 400** `BAD_REQUEST` with the parser message; blank `csvContent` returns **HTTP 400**
`VALIDATION_ERROR` (`csvContent is required`) and a two-character delimiter returns
`delimiter must be a single character`.

#### I. Circuit breaker & probe lease recovery

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
    "audit": [],
    "fallbackCache": {
      "hits": 0,
      "misses": 3,
      "expired": 0,
      "evictions": 0,
      "size": 1,
      "ttlMillis": 60000,
      "maxEntries": 256
    }
  },
  "message": "Resilience state retrieved",
  "timestamp": "2026-01-01T00:00:31Z"
}
```

| Field | Meaning |
| :--- | :--- |
| `probeInFlight` | `true` while a `HALF_OPEN` probe claim is held and its lease has not expired |
| `probeLeaseExpiresAt` | Deadline of that claim (`null` when the breaker holds no claim) |
| `fallbackCache` | Last-known-good cache snapshot: `hits`, `misses`, `expired`, `evictions`, live `size`, and the configured `ttlMillis` / `maxEntries`. `misses` counts every opt-in lookup that found nothing usable, including expired entries |

Recovery: while a probe lease is live a concurrent caller is refused with `503` `CIRCUIT_OPEN` and reason
`probe already in flight`. Once the lease expires the claim is superseded — the superseded generation can no
longer change counters, state or the audit trail, so a probe that hung inside the delegate and returns late is
ignored — and the next admitted caller claims a fresh generation. The scheduled reaper records exactly one
`PROBE_LEASE_EXPIRED` audit row per expired generation, so the breaker recovers even with no further traffic.

| Property | Default | Purpose |
| :--- | :--- | :--- |
| `utiltools.resilience.probe-lease-seconds` | `10` | Lifetime of a `HALF_OPEN` probe claim before it may be superseded |
| `utiltools.resilience.probe-reaper-interval-ms` | `5000` | Fixed delay between scheduled sweeps that release expired leases |
| `utiltools.resilience.fallback-cache-ttl-ms` | `60000` | Age after which a last-known-good fallback entry is expired and no longer served |
| `utiltools.resilience.fallback-cache-max-entries` | `256` | Hard cap on cached fallback entries; the oldest entry is evicted first |
| `utiltools.resilience.fallback-cache-sweep-interval-ms` | `30000` | Fixed delay between scheduled sweeps that drop expired fallback entries |

#### J. Last-known-good fallback for circuit-broken JSON queries

`POST /api/v1/utils/json/query` fails closed with `503` `CIRCUIT_OPEN` while the breaker is `OPEN`. A
caller that would rather receive a clearly marked stale answer can opt in:

```bash
curl -X POST http://localhost:8080/api/v1/utils/json/query \
  -H 'Content-Type: application/json' \
  -d '{"json": "{\"items\": [10, 20, 30]}", "jsonPath": "$.items[1]", "allowStaleFallback": true}'
```
```json
{
  "success": true,
  "message": "JSON query executed successfully",
  "data": {
    "jsonPath": "$.items[1]",
    "result": 20,
    "staleFallback": true,
    "cachedAt": "2026-01-01T00:00:05Z",
    "cacheAgeMillis": 1500
  },
  "timestamp": "2026-01-01T00:00:06.5Z"
}
```

`allowStaleFallback` is absent/false by default, and a **live** result keeps the exact pre-existing
shape apart from the three additive `null` fields (`staleFallback`, `cachedAt`, `cacheAgeMillis` are
populated only when a cached result is served).

**Contract:**

| Case | Result |
| :--- | :--- |
| Breaker `CLOSED` | Live evaluation; the successful result is cached before it is returned |
| Breaker `OPEN`, `allowStaleFallback: true`, fresh entry for the same `json` + `jsonPath` | `200` with the cached payload plus `staleFallback: true`, the original `cachedAt` and the computed `cacheAgeMillis` |
| Breaker `OPEN`, no entry (never seen, or a different `json`/`jsonPath` pair) | `503` `CIRCUIT_OPEN` — the original exception, unchanged |
| Breaker `OPEN`, entry older than `utiltools.resilience.fallback-cache-ttl-ms` | `503` `CIRCUIT_OPEN`; the expired entry is dropped and counted as `expired` |
| Breaker `OPEN`, `allowStaleFallback` absent/false | `503` `CIRCUIT_OPEN`; the cache is not even consulted |

**Properties:**

| Property | Default | Purpose |
| :--- | :--- | :--- |
| `utiltools.resilience.fallback-cache-ttl-ms` | `60000` | Age after which a cached answer is stale and no longer served |
| `utiltools.resilience.fallback-cache-max-entries` | `256` | Hard entry cap; the oldest entry is evicted first |
| `utiltools.resilience.fallback-cache-sweep-interval-ms` | `30000` | Fixed delay between scheduled sweeps that drop expired entries |

**Guarantees and limits:**

- **Keys are content-derived.** An entry is keyed by the SHA-256 digest of the normalized `json` +
  `jsonPath` pair, so only a byte-identical request can read it. A different caller therefore sees
  another caller's *body* only when it submitted the identical deterministic query — but the cache
  is not caller-partitioned, so treat it as a shared, content-addressed cache, not a per-tenant one.
- **The fallback never touches the breaker.** Serving a stale answer makes no breaker call, changes
  no counter, does not extend or shorten the cooldown, and does not claim or release the `HALF_OPEN`
  probe lease — the `GET /api/v1/utils/resilience` snapshot is identical before and after, except for
  the `fallbackCache` counters.
- **Only live successes are cached.** A failed live query leaves the previously cached value intact,
  and a fallback response is never written back, so a stale answer can never become the new
  last-known-good.
- **Best effort.** A cache write or lookup fault is swallowed: it never turns a successful live query
  into an error and never masks the original `CIRCUIT_OPEN` exception.
- **Non-replayable audit.** An opt-in call is journalled as `JSON_QUERY_FALLBACK`, which is
  non-deterministic: replaying it answers `409` `REPLAY_CONFLICT` (`NON_DETERMINISTIC`). A journalled
  `JSON_QUERY` entry replays with the opt-in flag stripped, so a replay can never serve stale data.
- **Bounded by count, not bytes.** The entry cap bounds the number of cached answers, not their size;
  size `fallback-cache-max-entries` against the largest result slices you query.
- **In-process.** The cache dies with the JVM: no restart persistence, no cross-instance sharing.

---

## 🧾 Operation Journal & Replay Verification

All eighteen journalled utility operations — `datetime/convert`, `datetime/diff`, `datetime/current`,
`datetime/shift`, `crypto/hash`, `crypto/base64`, `crypto/url`, `crypto/jwt/decode`, `crypto/uuid`,
`json/format`, `json/validate`, `json/query`, `json/merge-patch`, `json/pointer`, `text/transform`,
`text/stats`, `text/replace`, `text/html-entities` — are recorded in a **bounded in-memory journal** so a failed
hash, JSON query, datetime conversion or text operation leaves a trace of what actually executed, and a retry
can be verified against the original outcome without ever mutating the original entry.
`GET /api/v1/utils/datetime/timezones` is read-only catalogue metadata and is not journalled.
An opt-in JSON query (`allowStaleFallback: true`) is journalled as `JSON_QUERY_FALLBACK` rather than
`JSON_QUERY`: that type is non-deterministic, because the stored answer may be a cached last-known-good
result, so it is rejected for replay with `NON_DETERMINISTIC`.

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

**The live owner renews its own lease.** A replay that runs longer than the lease is not abandoned:
while `replayInvoke` executes, the owning replay renews its claim every
`utiltools.audit.replay-heartbeat-seconds` (default half of `replay-lease-seconds`, minimum `1`,
validated positive at startup). Renewal is **owner-fenced** — it runs under the same journal lock as
every other transition and only extends `leaseExpiresAt` when the entry still exists, its replay is
`IN_PROGRESS`, the presented owner token is the one currently issued for that claim, and the lease
has not already lapsed. A stale token, missing entry, terminal replay or expired claim is refused
with no mutation at all, so a forgotten or superseded owner can never buy itself more time. The
schedule is cancelled in a `finally` block as soon as the operation finishes, so nothing is renewed
after completion; a renewal that fails is logged and never replaces the operation's own outcome.

Consequences: `completeReplay` accepts the latest renewed expiry, so a long replay finishes
`VERIFIED`/`MISMATCH` instead of failing with `409 REPLAY_CONFLICT`; the reaper's
`REPLAY_LEASE_EXPIRED` rollback reads the same renewed `leaseExpiresAt`, so a renewed claim never
produces a `REAPED` tombstone; and a renewal racing a reap resolves under the journal lock, so
exactly one of *renewed claim* or *reaped claim* wins. Renewal stops being possible the moment the
operation ends — a claim left `IN_PROGRESS` after that is still rolled back to `FAILED` exactly once
with reason `REPLAY_LEASE_EXPIRED`. Renewal exposes nothing new: owner tokens remain private to the
journal and only `leaseExpiresAt` (already part of the `replay` record) is returned by `list`,
`find` and `replay`.

| Property | Default | Meaning |
| :------- | :------ | :------ |
| `utiltools.audit.replay-lease-seconds` | `30` | Lifetime of a replay claim before it may be taken over or reaped |
| `utiltools.audit.replay-heartbeat-seconds` | half of the lease (`15`) | Cadence at which a running replay's owner renews its claim; minimum `1`, must be positive |
| `utiltools.audit.replay-lease-quarantine-threshold` | `3` | How many times one entry's replay claim may expire and be rolled back before the reaper fails the entry for good with `REPLAY_LEASE_QUARANTINED`; clamped to `>= 1` |

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

#### Sweep verification: `POST /api/v1/utils/audit/verify`

`POST /api/v1/utils/audit/verify?limit=N` verifies the journal in one bounded pass instead of one
call per entry. It snapshots up to `limit` `SUCCEEDED` entries newest-first (`limit` defaults to
`20`, is clamped to `utiltools.audit.max-entries`, and answers `400 BAD_REQUEST` when it is `< 1`
or non-numeric) and replays each candidate through **exactly the same** attempt path as
`POST /entries/{id}/replay`, so the two endpoints can never disagree about eligibility or outcome.

```bash
curl -X POST "http://localhost:8080/api/v1/utils/audit/verify?limit=50"
```

```json
{
  "success": true,
  "message": "Journal verification sweep completed",
  "data": {
    "limit": 50,
    "scanned": 3,
    "verified": 1,
    "mismatch": 1,
    "failed": 1,
    "skipped": 0,
    "results": [
      { "entryId": "3b7d9f2a1c5e4a60", "operation": "CRYPTO_HASH", "outcome": "VERIFIED", "detail": "result matches" },
      { "entryId": "9f1c0d7b2e4a5531", "operation": "CRYPTO_HASH", "outcome": "MISMATCH", "detail": "first mismatch at /digest" },
      { "entryId": "1a2b3c4d5e6f7081", "operation": "TEXT_STATS", "outcome": "FAILED", "detail": "replay failed: unknown field" }
    ]
  },
  "timestamp": "2026-10-31T00:52:11.120Z"
}
```

The sweep is **bounded**: one call inspects at most `limit` entries and never more than
`utiltools.audit.max-entries`, so an operator can always size the work. Aggregates are counted from
the rows themselves, so `verified + mismatch + failed + skipped == scanned == results.length`.

The sweep is **single-winner per entry**, exactly like a single replay: every candidate is claimed
through the same `claimReplay` lease. An entry whose claim is live is reported `SKIPPED` with
`skipReason = "LIVE_CLAIM"` and its `replay.state` / `leaseExpiresAt` are **not** touched — the
in-flight owner can still complete its own replay. Non-replayable (`NOT_REPLAYABLE`),
non-deterministic (`NON_DETERMINISTIC`), no-longer-`SUCCEEDED` (`NOT_SUCCEEDED`) and evicted
(`EVICTED`) entries are likewise `SKIPPED` with an explicit `skipReason` instead of failing the
call.

**Failure isolation:** an invocation that throws is recorded as `FAILED` with a bounded `detail`
(≤ 200 characters) and the sweep **continues** with the remaining candidates — one bad entry never
aborts the batch. Rows carry only `entryId`, `operation`, `outcome` / `skipReason` and `detail`;
the stored request and result payloads, owner tokens and lease tokens are never returned.

The sweep is **safe alongside the reaper and eviction**: it holds no journal lock while an
operation runs, so a concurrent reaper pass or an eviction (the journal is bounded by
`utiltools.audit.max-entries`) can interleave without deadlock, and an entry evicted mid-sweep is
reported `SKIPPED` / `EVICTED`. Every original `state`, `result`, `completedAt`, `request` and
`replayable` value is left exactly as it was — only the attached `replay` record changes. There is
no background sweep: verification runs only when an operator calls the endpoint.

### D. Inspect the reaper tombstone log

Every entry the background reaper reclaims leaves a bounded `ReapRecord` tombstone behind, so an
operator can see *why* it was reclaimed. The log is newest-first and read-only.

```bash
curl "http://localhost:8080/api/v1/utils/audit/reaps?limit=2&reason=STALE_PENDING"
```
```json
{
  "success": true,
  "message": "Reap tombstones retrieved successfully",
  "data": [
    {
      "status": "REAPED",
      "entryId": "9d2a5f7c1e0b3a41",
      "operation": "JSON_QUERY",
      "reason": "STALE_PENDING",
      "detail": "PENDING entry exceeded the reap threshold",
      "reapedAt": "2026-10-31T00:56:12.003Z"
    }
  ],
  "requestId": "corr-crypto5678",
  "timestamp": "2026-10-31T00:56:12.008Z"
}
```

`reason` accepts `REPLAY_LEASE_EXPIRED`, `REPLAY_LEASE_QUARANTINED`, `MANUALLY_REAPED`,
`STALE_PENDING` and `OVERSIZE_PAYLOAD` (omit for all); `limit` defaults to `50` and is clamped to `[1, max-entries]`. An unknown `reason`
value or a non-numeric `limit` answers `400 BAD_REQUEST` in the standard envelope. The route never
triggers a reap pass, never mutates an entry and never writes a journal record — polling it cannot
reclaim anything, and the log is in-process, so it is empty again after a restart.

---

### 4. Text Transformation & Statistics Utility

Deterministic, locale-independent (`Locale.ROOT`) text helpers. Every endpoint here is a pure function of
its request body, so every call is journalled and replayable.

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
| `SENTENCE_CASE` | first letter of each `[.!?]`-delimited sentence upper-cased, remaining letters lower-cased, all other characters copied through in place | `Hello world. Bye.` |

`operation` is case-insensitive on input and echoed back upper-case; surrounding whitespace is **not**
tolerated. An unknown operation, a whitespace-padded operation and a blank `operation` all fail bean
validation before the service is reached, answering HTTP `400` with error `VALIDATION_ERROR`, message
`Validation failed` and the supported operation list in `details`; blank `text` likewise answers
`400 VALIDATION_ERROR`.

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

#### Replace

```bash
curl -X POST http://localhost:8080/api/v1/utils/text/replace \
  -H 'Content-Type: application/json' \
  -d '{"text": "a1 b2", "pattern": "(\\w)(\\d)", "replacement": "$2$1"}'
```

```json
{
  "success": true,
  "message": "Text replaced successfully",
  "data": {
    "result": "1a 2b",
    "replacements": 2,
    "changed": true,
    "length": 5
  },
  "timestamp": "2026-01-01T00:00:00Z",
  "requestId": "3d9b7e10-4c2a-4f6b-8e51-9a0c1d2b3e44"
}
```

| Field | Type | Required | Default | Meaning |
| :--- | :--- | :--- | :--- | :--- |
| `text` | string | yes | — | Input text, at most 100000 characters |
| `pattern` | string | yes | — | Literal or regular-expression pattern, at most 512 characters |
| `replacement` | string | yes | — | Replacement text, at most 100000 characters |
| `all` | boolean | no | `true` | `true` replaces every match, `false` replaces only the first |
| `literal` | boolean | no | `false` | `true` treats `pattern` and `replacement` verbatim, `false` treats `pattern` as a regex |
| `caseInsensitive` | boolean | no | `false` | `true` matches regardless of case, folding **ASCII letters only** (`Pattern.CASE_INSENSITIVE` without `UNICODE_CASE`) |

| Response field | Definition |
| :--- | :--- |
| `result` | the text after replacement |
| `replacements` | number of matches actually replaced (`0` when the pattern does not match) |
| `changed` | `true` when `result` differs from the supplied `text` |
| `length` | **Unicode code-point count of `result`** (an emoji counts once) |

**Literal vs regex.**

* `literal=false` (default) compiles `pattern` as a `java.util.regex` pattern, so metacharacters are
  special and `$n` / `${name}` group references expand in `replacement`. For example
  `{"text": "2026-01-02", "pattern": "(\\d{4})-(\\d{2})-(\\d{2})", "replacement": "$3/$2/$1"}`
  returns `"02/01/2026"`.
* `literal=true` quotes both the pattern (`Pattern.quote`) and the replacement
  (`Matcher.quoteReplacement`), so `{"text": "a.b", "pattern": ".", "replacement": "X", "literal": true}`
  returns `"aXb"` with `replacements: 1`, and a `$` or `\\` in `replacement` stays a literal character.
* `all=false` replaces only the first match: `{"text": "aaa", "pattern": "a", "replacement": "b", "all": false}`
  returns `"baa"` with `replacements: 1`.
* `caseInsensitive=true`: `{"text": "Hello HELLO", "pattern": "hello", "replacement": "bye", "caseInsensitive": true}`
  returns `"bye bye"` with `replacements: 2`. Folding is ASCII-only — the pattern is compiled with
  `Pattern.CASE_INSENSITIVE` and no `UNICODE_CASE`, so `É`/`é` do **not** match each other; spell both cases
  in the pattern (e.g. `[Éé]`) when you need non-ASCII folding.

**Errors and bounds.** Blank `text`/`pattern`, an absent `replacement` or an over-limit `text`/`pattern`/
`replacement` answer `400 VALIDATION_ERROR` with the offending constraint messages. An invalid regex (or an
out-of-range `$n` group reference) answers `400 BAD_REQUEST` with `Invalid regex pattern '<pattern>'`. Because a
100000-character text with 100000 one-character matches and a 100000-character replacement would allocate 10^10
characters, the result is bounded to **1000000 characters**. The bound is enforced *before* the result is
materialised: `replace` accumulates the output match by match with `Matcher.appendReplacement` and checks each
append against the exact expanded length of that match's replacement (so `$n`/`${name}` group references cannot
smuggle an oversized expansion past the check), and the trailing unmatched text is checked too. A request that
would exceed the bound answers `400 BAD_REQUEST` with `replacement result exceeds 1000000 characters` instead of
allocating, and only the matches actually replaced count — `all=false` is bounded by its single replacement, not
by every match. There is no regex
timeout in this service, so keep untrusted patterns simple — the route sits behind the standard
`/api/v1/utils/**` rate limit.

#### HTML entities

```bash
curl -X POST http://localhost:8080/api/v1/utils/text/html-entities \
  -H 'Content-Type: application/json' \
  -d '{"action": "ENCODE", "text": "Fish & chips <b>today</b>"}'
```

```json
{
  "success": true,
  "message": "HTML entities processed successfully",
  "data": {
    "action": "ENCODE",
    "result": "Fish &amp; chips &lt;b&gt;today&lt;/b&gt;",
    "changed": true,
    "length": 41
  },
  "timestamp": "2026-01-01T00:00:00Z",
  "requestId": "3d9b7e10-4c2a-4f6b-8e51-9a0c1d2b3e44"
}
```

| Field | Type | Required | Default | Meaning |
| :--- | :--- | :--- | :--- | :--- |
| `action` | string | yes | — | `ENCODE` or `DECODE`, matched **case-insensitively** (`encode` works); anything else is a validation error |
| `text` | string | yes | — | Input text, non-blank; there is no request-size cap, only the 1000000-character bound on `result` (see **Errors and bounds**) |

| Response field | Definition |
| :--- | :--- |
| `action` | the caller's `action` echoed back verbatim (`encode` stays `encode`, like `/transform`) |
| `result` | the escaped (ENCODE) or unescaped (DECODE) text |
| `changed` | `true` when `result` differs from the supplied `text` |
| `length` | **Unicode code-point count of `result`** (an emoji counts once) |

**ENCODE.** Exactly five characters are escaped, ampersand **first** so no double-encoding or
re-escaping of earlier replacements can happen: `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`, `"` → `&quot;`,
`'` → `&#39;`. Apostrophes use the numeric form `&#39;` because `&apos;` is not defined in HTML 4. Text
without any of the five characters is returned unchanged with `changed: false`. This is a **codec, not a
sanitiser**: tags, attributes and scripts are escaped, never removed — render the result as HTML text, and
sanitise elsewhere if you need a security boundary.

**DECODE.** Resolves the five named entities (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;`), decimal numeric
references (`&#NN;`, e.g. `&#65;` → `A`) and hexadecimal numeric references (`&#xHH;` / `&#XHH;`, e.g.
`&#x41;` → `A`). A single decode pass runs left to right, so `&amp;lt;` becomes `&lt;` (not `<`). Everything
else is preserved **verbatim**, character for character: unknown named entities (`&nbsp;`, `&copy;`),
malformed references (`&`, `&#;`, `&#x;`, `&#99999999;`, a missing semicolon such as `&#65`), and references
to code points that are not valid Unicode scalar values (`&#0;`, surrogate halves such as `&#xD800;`, values
above `U+10FFFF`) — no error, no substitution, no data loss. Numeric bodies are length-capped, not
normalised: decimal bodies carry at most 7 digits and hexadecimal bodies at most 6, so `&#00065;` → `A`
and `&#x000041;` → `A` decode, while a longer body such as `&#00000065;` is preserved verbatim.
**DECODE inverts escaping; it does not sanitise.** The output is plain text with references resolved and
is *less* safe to render than its input — `&lt;script&gt;` becomes `<script>`. Never use `DECODE` to
clean untrusted markup; sanitise with a dedicated HTML sanitiser and treat the result as untrusted.

**Round-trip.** `DECODE(ENCODE(text))` reproduces `text` exactly for any text, because every character the
encoder emits is one the decoder understands.

**Errors and bounds.** Blank `text`, an absent `text`, or an `action` other than `ENCODE`/`DECODE` answers
`400 VALIDATION_ERROR` (through `GlobalExceptionHandler`) and is not journalled. The output is bounded by the
existing `MAX_RESULT_CHARS` (1000000 code points): a text whose encoding would exceed it answers
`400 BAD_REQUEST` with `html-entity result exceeds 1000000 characters`, raised before the result is
materialised beyond the bound.

#### Journal & replay

All four endpoints call `AuditedOperations`, so every call is recorded as `TEXT_TRANSFORM`,
`TEXT_STATS`, `TEXT_REPLACE` or `TEXT_HTML_ENTITIES` in `GET /api/v1/utils/audit/entries`, with the request (redacted like every other
operation) and the response. A replace that compiles and reaches `TextService` but then fails — invalid regex,
an out-of-range `$n` group reference, or the output bound — is recorded as `TEXT_REPLACE` with `state = FAILED`
and `errorCode = BAD_REQUEST`. A *bean-validation* rejection (blank `text`/`pattern`, absent `replacement`,
over-limit fields) is raised while Spring MVC resolves the `@Valid` argument, before the controller body runs, so
— exactly like `/transform` and `/stats` — it answers `400 VALIDATION_ERROR` and is **not** journalled. All four
are deterministic, so
`POST /api/v1/utils/audit/entries/{id}/replay` re-runs the stored request and answers
`replay.state = VERIFIED` with `detail = results match` without mutating the entry's original `state`, `result`
or `completedAt`.
A body larger than `utiltools.audit.max-replay-bytes` is still processed but stored as
non-replayable, so its replay answers the existing `409 REPLAY_CONFLICT` (a replace request with a 100000-character
`text` and `replacement` is large enough to hit this).

---

### 5. Batch Hash Jobs (async)

Hash up to **1000 items** in the background instead of blocking one HTTP request per item. Submission is
accepted immediately with **202** and a `jobId`; you then poll `GET /api/v1/utils/jobs/{jobId}` until the job
reaches `COMPLETED` or `FAILED`, or withdraw it with `DELETE /api/v1/utils/jobs/{jobId}` while it is still
`QUEUED`, or requeue a `FAILED` job with `POST /api/v1/utils/jobs/{jobId}/retry` while it still has an attempt
left. Every item is hashed with the same `CryptoService.hash` used by
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
    "completedAt": "2026-10-31T19:12:04.421907Z",
    "completedItems": 3,
    "succeededItems": [0, 1, 2],
    "failedItems": []
  },
  "timestamp": "2026-10-31T19:12:05.012775Z"
}
```

The state machine is `QUEUED → RUNNING → COMPLETED | FAILED` plus `QUEUED → CANCELLED`, the in-flight
`RUNNING → CANCELLING → CANCELLED` cancellation (see [C](#c-cancel-a-job-200-ok-idempotent)) and the bounded
`FAILED → QUEUED` retry transition (see [D](#d-retry-a-failed-job-202-accepted)); `COMPLETED`, `CANCELLED` and
`FAILED`-at-`maxAttempts` are terminal, while `QUEUED`, `RUNNING` and `CANCELLING` are not. A `FAILED` job carries a non-null `error` and a `completedAt`, and never
blocks later jobs.

Every snapshot also carries the attempt bookkeeping: `attempt` (1-based, `1` for a first submission and
incremented by each accepted retry), `maxAttempts` (the configured ceiling) and `attempts`, a bounded history
where each entry is `{ "attempt", "status", "startedAt", "completedAt", "error", "auditEntryId",
"resumedFrom", "succeededItems", "failedItems" }`, with `resumedFrom` the 0-based input index the execution
started at (`0` for a first attempt and for a retry that inherited no progress), `auditEntryId` the `BATCH_HASH`
journal entry this attempt wrote (absent when the attempt never started or no journal is wired), and
`succeededItems` / `failedItems` the ascending item indices **that attempt** hashed and that threw (empty lists
when it has none, so both are present on a `RUNNING` row too). Note the scope distinction: an attempt row reports
its own indices, while the job-level `succeededItems` / `failedItems` described below are job-wide and therefore
also include the progress a resumed attempt inherited. The history holds at most one entry per attempt, so it can
never exceed `maxAttempts`. All of these fields are additive: existing fields keep their names and meaning.

Hashing is **checkpointed per item** and **isolated per item**: an item whose hash throws is recorded as failed
and the worker carries on with the remaining items, so one bad item never aborts the whole batch. `completedItems`
(additive, `0..itemCount`) reports **how many items are durably hashed** — it is the size of the per-item
checkpoint (`checkpoint.size()`), not a contiguous leading prefix, so a partially failed batch can report `2`
while an item at an index below that count failed. It advances one item at a time as the worker commits each
digest, so a `RUNNING` job shows real progress and a `FAILED` one shows how much survived. `succeededItems` and
`failedItems` (additive, ascending input indices, always present, empty when the set is empty) are the per-item
projections: `succeededItems` lists every index whose digest is durably retained and `failedItems` every index
whose hash threw — so a partially failed batch reads as "these indices hashed, those threw" instead of a
whole-job failure. Both carry **indices only, never a digest or item content**. The checkpoint is private to the
job — `results` stays absent until the job `COMPLETES`, and a `CANCELLING` or `CANCELLED` job withdrew its
progress, so it reports `completedItems: 0` with both index lists empty — so a poller can watch progress without
ever seeing a partial digest. The checkpoint lives in memory with the rest of the job registry: eviction, expiry
and restart discard it.

#### C. Cancel a job (200 OK, idempotent)

`DELETE /api/v1/utils/jobs/{jobId}` cancels a job that is still `QUEUED` **or** already `RUNNING`.
Cancellation is cooperative: the request is recorded immediately and honoured at the worker's next checkpoint,
so a poll may briefly show `CANCELLING` before the job becomes `CANCELLED`.

- A `QUEUED` job moves `QUEUED → CANCELLED` exactly once and **nothing is hashed**: `startedAt` and `results`
  stay absent, and `completedAt` is set to the instant the cancellation was accepted.
- A `RUNNING` job moves `RUNNING → CANCELLING` and answers `200` with `status: CANCELLING`. The per-client quota
  slot stays held and the execution lease stays in place while the cancellation is in flight — the job is still
  non-terminal, so it keeps occupying its slot and stays in listings. The worker notices `CANCELLING` at its next
  checkpoint (before the next item, or as soon as a blocked `hash` for the current item returns), discards the
  private checkpoint prefix, and writes `CANCELLED` exactly once; only that terminal write releases the quota.
- If the worker never returns, the lease reaper terminalizes a `CANCELLING` job whose execution lease has lapsed
  to `CANCELLED`, and the drain that runs when the application stops does the same, so a cancellation can never
  leave a job non-terminal forever.

A `CANCELLING` job reports `completedItems: 0` and no `results`, so a partial digest is never disclosed, and it
is never evicted (it has no `completedAt` yet). A repeated `DELETE` on a `CANCELLING` (or `CANCELLED`) job is
idempotent and returns the current snapshot; a `COMPLETED` or `FAILED` job answers
`409 JOB_CANCELLATION_CONFLICT`, because there is nothing left to cancel.

```bash
curl -i -X DELETE http://localhost:8080/api/v1/utils/jobs/3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3
```

**Response — `200 OK`, `CANCELLED` (the job was still `QUEUED`):**
```json
{
  "success": true,
  "message": "Job cancelled",
  "data": {
    "jobId": "3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3",
    "idempotencyKey": "batch-2026-10-31-001",
    "status": "CANCELLED",
    "itemCount": 3,
    "completedItems": 0,
    "succeededItems": [],
    "failedItems": [],
    "createdAt": "2026-10-31T19:12:04.418273Z",
    "completedAt": "2026-10-31T19:12:04.502114Z"
  },
  "timestamp": "2026-10-31T19:12:04.502381Z"
}
```

**Response — `200 OK`, `CANCELLING` (the job was already `RUNNING`):**
```json
{
  "success": true,
  "message": "Job cancelled",
  "data": {
    "jobId": "3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3",
    "idempotencyKey": "batch-2026-10-31-001",
    "status": "CANCELLING",
    "itemCount": 3,
    "completedItems": 0,
    "succeededItems": [],
    "failedItems": [],
    "createdAt": "2026-10-31T19:12:04.418273Z",
    "startedAt": "2026-10-31T19:12:04.420118Z"
  },
  "timestamp": "2026-10-31T19:12:04.502381Z"
}
```

`completedAt` is absent while the job is `CANCELLING` (the retention clock starts at the terminal write) and
`results` is always absent until the job `COMPLETES`. Polling the same id afterwards shows `CANCELLED` with
`completedAt` set, `results` absent and `completedItems: 0`.

#### D. Retry a failed job (202 Accepted)

`POST /api/v1/utils/jobs/{jobId}/retry` requeues a `FAILED` job for one more attempt. Only the
`FAILED → QUEUED` transition is allowed, and it happens in one step under the job's monitor: the status check,
the `attempt` increment and the fresh execution are atomic, so two concurrent retries cannot both be admitted
and no worker can complete the job between the check and the requeue. The `jobId` and its `idempotencyKey`
mapping are preserved — a retry reuses the original job, which is exactly what a transient failure needs.

```bash
curl -i -X POST http://localhost:8080/api/v1/utils/jobs/3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3/retry
```

**Response — `202 Accepted`, requeued as attempt 2:**
```json
{
  "success": true,
  "message": "Job retried",
  "data": {
    "jobId": "3f6b0c2e-9a54-4f01-8d47-5b2da1f0e8c3",
    "idempotencyKey": "batch-2026-10-31-001",
    "status": "QUEUED",
    "itemCount": 3,
    "attempt": 2,
    "maxAttempts": 3,
    "attempts": [
      { "attempt": 1, "status": "FAILED", "startedAt": "2026-10-31T19:12:04.420118Z",
        "completedAt": "2026-10-31T19:12:04.421907Z", "error": "BATCH_ITEM_HASH_FAILED",
        "auditEntryId": "6d1c2a1e-7b52-4d3f-9a3c-4c1a2b7e5d90", "resumedFrom": 0,
        "succeededItems": [0, 2], "failedItems": [1] }
    ],
    "createdAt": "2026-10-31T19:12:04.418273Z",
    "completedItems": 2,
    "succeededItems": [0, 2],
    "failedItems": [1]
  },
  "timestamp": "2026-10-31T19:12:09.004112Z"
}
```

`startedAt`, `completedAt`, `results` and `error` are cleared by the requeue, so the retried attempt's outcome is
written fresh on the next poll. A retry does **not** rehash from item `0`: the checkpoint survives the
`FAILED → QUEUED` transition, `completedItems`, `succeededItems` and `failedItems` keep reporting it (still
without publishing `results`), and the retried attempt resumes at the first **unprocessed** index. That equals
`completedItems` only when no item failed mid-batch: indices that already succeeded **and** indices already
recorded as failed are both skipped, so the retried attempt hashes only what is still unprocessed — a failed index
stays failed and is not retried by the same job, and a success is never rehashed. That applies to interruptions too:
a `JOB_DEADLINE_EXCEEDED`, or a reaped `EXECUTION_LEASE_EXPIRED` that left the tail of the batch unvisited, retries
only the unvisited suffix — for a batch of up to 1000 items that is a suffix-sized retry instead of a full re-run.
This example has no such suffix: every index is already recorded (`2` succeeded, `1` failed), so its retried
attempt has nothing left to hash and re-enters `FAILED` with `BATCH_ITEM_HASH_FAILED` on the next poll as `attempt` 2,
without re-hashing `0`, `1` or `2`. Workers are fenced by generation: each execution captures the
`attempt` it was admitted under and commits an item, or writes `RUNNING`, `COMPLETED` or `FAILED`, **only** if
that generation is still current. A late worker from attempt 1 therefore cannot append to the checkpoint or
overwrite the retried attempt's status, results or error — and the attempt history shows where each execution
started via `resumedFrom`.

A batch with mixed outcomes ends `FAILED` with `error: BATCH_ITEM_HASH_FAILED` once the worker has walked the
whole batch: it fails only when at least one item threw **and** not every item ended up durably hashed, never
because of an individual item on its own. `results` stays absent, the per-item causes (throwable type and message,
bounded) ride the closing `BATCH_HASH` journal entry together with `checkpointItems`, `itemSuccesses` and
`itemFailures`, and the snapshot's `succeededItems` / `failedItems` disclose **indices only** — never a digest or
item content. A batch whose every item hashed `COMPLETES` normally, reporting `succeededItems: [0..itemCount-1]`
and `failedItems: []`. Partial failure does not disturb the lease: the worker renews its execution lease before
each item it hashes, so an intermittently failing batch keeps the lease alive across items, and a lease nobody
renews still lapses into `EXECUTION_LEASE_EXPIRED` through the reaper exactly as before.

That reaper can also **redrive** such a job instead of failing it. With
`utiltools.jobs.max-auto-failovers=N` (`N > 0`; the default `0` disables it) an expired execution lease on a
`RUNNING` job whose `attempt < maxAttempts`, whose `deadlineAt` has not been reached and whose status is not
`CANCELLING` is closed as `FAILED` / `EXECUTION_LEASE_EXPIRED` in its attempt history and its audit entry, and the
job moves to `QUEUED` at `attempt + 1` in the same reaper pass. The failover keeps the job's `checkpoint`,
`failedItems`, `itemErrors` and `deadlineAt` - so the redriven attempt resumes at the first unprocessed index and
the total-life bound is not extended - and it keeps the per-client quota and global-capacity reservation without a
second increment, so no capacity is leaked or double-counted. The snapshot's additive `autoFailovers` field reports
how many redrives succeeded; a manual `POST …/retry` neither increments nor resets it. The budget is bounded
twice over: at most `N` auto-failovers per job and never beyond `maxAttempts`. A job that exhausts either budget
still ends `FAILED` / `EXECUTION_LEASE_EXPIRED` with its reservation released exactly once, a deadline-expired job
still ends `JOB_DEADLINE_EXCEEDED` without a failover, a `CANCELLING` job still terminalises `CANCELLED`, and a
failover whose lane hand-off is refused during drain or teardown rolls back to the exact pre-reap `FAILED`
snapshot, attempt number and error without consuming the budget. Journal accounting stays exactly-once per attempt:
the lapsed attempt's `BATCH_HASH` entry closes `FAILED` once and is never reopened, and the failover attempt opens
its own entry only when a worker admits it.

A `RUNNING` worker may also **renew its execution lease** with
`POST /api/v1/utils/jobs/{jobId}/heartbeat` (no request body). Each successful renewal sets
`executionLeaseExpiresAt` to `Instant.now(clock)` plus `utiltools.jobs.execution-lease-seconds` (default `30`),
stamps `lastHeartbeatAt` with that same instant and writes one closed `BATCH_HASH` journal row marked
`HEARTBEAT` with the bounded detail `lease renewed via heartbeat`; nothing else in the snapshot changes —
`status`, `results`, `error`, `completedAt`, `startedAt`, `checkpoint` and `attemptHistory` are untouched. Only
the attempt that is currently `RUNNING` can renew: an unknown or already-evicted `jobId` is `404 JOB_NOT_FOUND`,
a `QUEUED`, `CANCELLING`, `COMPLETED` or `CANCELLED` job is `409 JOB_RETRY_CONFLICT`, and a lease that has
**already expired** is `400 BAD_REQUEST` rather than being revived, so a late worker from a superseded attempt
cannot buy its generation more time. A lease that is never renewed still lapses on schedule,
`reapExpiredExecutions` marks the job `FAILED` exactly as before, and the retry path then starts
`attempt + 1`.

Ineligible retries are rejected **without mutating state**: an unknown or already-evicted `jobId` is
`404 JOB_NOT_FOUND`; a `QUEUED`, `RUNNING`, `CANCELLING`, `COMPLETED` or `CANCELLED` job is
`409 JOB_RETRY_CONFLICT`; and a
`FAILED` job whose `attempt` already equals `maxAttempts` answers the same `409` with `attempt` and
`maxAttempts` in `details`. If the queue is saturated the requeue is rolled back — the previous `FAILED`
snapshot is restored, checkpoint and `completedItems` included, the attempt is **not** consumed, and the caller
gets the same `429 QUEUE_FULL` as on submission, so the retry can simply be repeated once capacity frees.

Cancellation is idempotent: repeating the same `DELETE` on an already-`CANCELLED` job returns **`200`** with the
identical snapshot (`jobId`, `status`, `completedAt` unchanged) rather than a conflict, and repeating it on a
`CANCELLING` job returns **`200`** with the same `CANCELLING` snapshot (the request is already recorded). A
`COMPLETED` or `FAILED` job returns `409 JOB_CANCELLATION_CONFLICT` (see [E](#e-failure-modes)), and an unknown or
already-evicted `jobId` returns `404 JOB_NOT_FOUND`. The cancelled snapshot — and its `idempotencyKey` mapping —
stays pollable for `utiltools.jobs.retention-seconds` and is then evicted like any other terminal job, so a
duplicate submission with the same key observes the `CANCELLED` snapshot until the retention cutoff elapses.

#### E. Failure modes

| Situation | HTTP | `error` | What to do |
| :--- | :--- | :--- | :--- |
| Bounded executor queue is saturated | `429 TOO_MANY_REQUESTS` | `QUEUE_FULL` | Retry with the **same** `idempotencyKey` once capacity frees. The rejected submission is rolled back atomically — its job row **and** its idempotency mapping are both removed, so it leaves no residue and the retry with that key starts a brand-new job |
| Unknown or already-evicted `jobId` | `404 NOT_FOUND` | `JOB_NOT_FOUND` | Resubmit the batch |
| Cancelling a job that is `COMPLETED` or `FAILED` | `409 CONFLICT` | `JOB_CANCELLATION_CONFLICT` | Stop cancelling; poll `GET` for the terminal snapshot. A `QUEUED` or `RUNNING` job is cancelled instead: `DELETE` answers `200` with `CANCELLED` or `CANCELLING`, and a repeat `DELETE` on a `CANCELLING` or `CANCELLED` job is `200`, not a conflict |
| Retrying a `QUEUED`, `RUNNING`, `CANCELLING`, `COMPLETED` or `CANCELLED` job, or a `FAILED` job at `maxAttempts` | `409 CONFLICT` | `JOB_RETRY_CONFLICT` | Only a `FAILED` job below `maxAttempts` can be retried. The job snapshot is unchanged by the rejection; resubmit if the attempt budget is spent |
| Retry rejected because the bounded queue is saturated | `429 TOO_MANY_REQUESTS` | `QUEUE_FULL` | Retry `POST …/retry` again once capacity frees: the previous `FAILED` snapshot and its `attempt` are restored, so nothing is consumed |
| The client already holds `utiltools.jobs.max-active-jobs-per-client` non-terminal jobs | `429 TOO_MANY_REQUESTS` | `JOB_QUOTA_EXCEEDED` | Retry once one of your **own** jobs finishes — the quota is per client identity, so other callers are unaffected. `details.limit` is the ceiling and `details.activeJobs` is your current count. Nothing is registered on a rejected submission (no job row, no `idempotencyKey` mapping, no consumed slot), and a rejected `POST …/retry` restores the previous `FAILED` snapshot with its `attempt` unchanged. A resubmission that repeats the same `idempotencyKey` **and** payload is a duplicate, not a new job: it returns the existing snapshot instead of a quota error |
| Same `idempotencyKey` reused with a different `algorithm`, `items` or `secretKey` | `409 CONFLICT` | `IDEMPOTENCY_CONFLICT` | The first job is untouched and keeps running; the conflicting submission is discarded before registration (no job, no execution, no key residue). Pick a new `idempotencyKey` for a genuinely new payload |
| The job's total-life bound (`deadlineSeconds`, or the configured `utiltools.jobs.default-deadline-seconds`) is reached — in the queue or mid-execution | `202`, then `FAILED` on poll | — (`data.error` = `JOB_DEADLINE_EXCEEDED`) | The bound covers queue wait plus execution and fires even while the execution lease is live; it is distinct from `EXECUTION_LEASE_EXPIRED` (a stalled worker). `completedItems`, `succeededItems` and `failedItems` report the retained progress and `results` stays `null` — no partial digest is published — so `POST …/retry` starts `attempt + 1` with a fresh deadline and resumes at the first unprocessed index |
| One item's hash throws during execution (`REQ-01`) | `202`, then `FAILED` on poll | — (`data.error` = `BATCH_ITEM_HASH_FAILED`) | Don't resubmit: the batch is not aborted, the failing index is recorded `FAILED`, every remaining item is still hashed, and the job ends `FAILED` only when not every item is durably hashed. The per-item causes are journalled (content-free), `results` stays `null`, and `succeededItems` / `failedItems` expose indices only. `POST …/retry` resumes at the first unprocessed index, skipping both succeeded and failed indices |
| Unsupported `algorithm` | `202`, then `FAILED` on poll | — (`data.error` = `BATCH_ITEM_HASH_FAILED`) | The rejection is per item like any other item-level failure: every index throws `Unsupported hash algorithm: …`, so `failedItems` is `0..itemCount-1`, `completedItems` stays `0`, `results` stays `null` and the job ends `FAILED`. Fix the algorithm and resubmit with a **new** `idempotencyKey` (or none) — `POST …/retry` re-runs the same rejected algorithm, and since every index is already recorded failed it has nothing left to hash and re-enters `FAILED` with this same code. Reusing the failed job's key replays its cached `FAILED` snapshot instead of re-executing |
| Blank/empty `items` | `400 BAD_REQUEST` | `VALIDATION_ERROR` | Send a non-empty list of non-blank strings (max 1000) |

#### F. Batch job configuration

```properties
# Batch hash jobs (bounded async execution)
utiltools.jobs.worker-count=2
utiltools.jobs.queue-capacity=100
utiltools.jobs.retention-seconds=300
utiltools.jobs.cleanup-interval-ms=60000
utiltools.jobs.max-attempts=3
utiltools.jobs.max-active-jobs-per-client=20
utiltools.jobs.default-deadline-seconds=0
utiltools.jobs.max-retained-jobs=1000
utiltools.jobs.max-auto-failovers=0
```

| Property | Default | Meaning |
| :--- | :--- | :--- |
| `utiltools.jobs.worker-count` | `2` | Worker threads hashing batches; bounds CPU-wide concurrency |
| `utiltools.jobs.queue-capacity` | `100` | `ArrayBlockingQueue` slots for accepted-but-not-started jobs; full queue ⇒ `429 QUEUE_FULL` (`AbortPolicy`) |
| `utiltools.jobs.retention-seconds` | `300` | How long a `COMPLETED`/`FAILED`/`CANCELLED` job (plus its idempotency key) is kept before eviction |
| `utiltools.jobs.cleanup-interval-ms` | `60000` | Interval of the scheduled eviction pass (`@EnableScheduling`); `QUEUED`/`RUNNING`/`CANCELLING` jobs are never evicted, and `CANCELLED` jobs are evicted on the same retention cutoff as `COMPLETED`/`FAILED` |
| `utiltools.jobs.max-attempts` | `3` | Ceiling on execution attempts per job, where the first submission is attempt `1`; a `FAILED` job at this count answers `409 JOB_RETRY_CONFLICT`. Values below `1` are clamped to `1`. Global — it is not settable per request |
| `utiltools.jobs.default-deadline-seconds` | `0` | Server default total-life bound (seconds) applied when a request omits `deadlineSeconds`; it covers **queue wait plus execution** and, once `deadlineAt` passes, moves the job to `FAILED` with `JOB_DEADLINE_EXCEEDED` while keeping the checkpoint prefix for a retry. `0` disables the default, so a deploy that never sets it behaves exactly as before. Anything outside `0..3600` fails startup |
| `utiltools.jobs.max-active-jobs-per-client` | `20` | Ceiling on one client's concurrent **non-terminal** jobs (`QUEUED`, `RUNNING` or `CANCELLING`), keyed by the same identity the rate limiter resolves (see [G](#g-rate-limiting-interaction-explicit-decision)); one more submission answers `429 JOB_QUOTA_EXCEEDED` with `limit` and `activeJobs` in `details`. A slot is released as each job reaches `COMPLETED`, `FAILED` or `CANCELLED` — a `DELETE` on a `RUNNING` job records the cancellation without releasing the slot, which is released once by the terminal `CANCELLED` write. Values below `1` fail startup. Set it at or above `worker-count + queue-capacity` to restore pre-quota (unbounded per client) admission |
| `utiltools.jobs.max-retained-jobs` | `1000` | Hard cap on retained **terminal** rows (`COMPLETED`, `FAILED`, `CANCELLED`) in the in-memory registry, enforced together with `retention-seconds`: once the cap is exceeded the oldest terminal job by `completedAt` is unregistered (with its idempotency key) so a fast submit/complete churn cannot grow the registry without bound. `QUEUED`, `RUNNING` and `CANCELLING` rows are never evicted, so the registry stays at or below `max-retained-jobs + worker-count + queue-capacity` rows. Values below `1` fail startup |

| `utiltools.jobs.max-auto-failovers` | `0` | Bounded automatic recovery from a lapsed execution lease: `0` keeps today's behaviour (a reaped `RUNNING` job ends `FAILED` / `EXECUTION_LEASE_EXPIRED` and needs `POST …/retry`), `N` lets the reaper redrive such a job as `attempt + 1` from its checkpoint at most `N` times per job. Negative values fail startup. Global - it is not settable per request |

#### F. Fair per-client queue admission (bounded starvation)

Accepted jobs are **not** handed straight to the worker pool. Each submission first joins a
per-client FIFO *lane* keyed by the same normalized client identity the rate limiter and the
per-client quota resolve, and a single dispatcher thread drains the lanes in **round-robin**
order, admitting one queued attempt at a time to the bounded executor
(`worker-count` running + `queue-capacity` slots). Two guarantees follow:

- **Bounded starvation (round barrier).** The dispatcher keeps the set of clients it has
  already served in the *current round*. A client is eligible only while its key is **absent**
  from that set, so each client is admitted at most once per round; as soon as no still-queued
  client is eligible the dispatcher closes the round by clearing the set, and every queued
  client becomes eligible again. Consequence, stated exactly: while two or more clients hold
  runnable queued work, no client is admitted a second time before every other queued client
  has been admitted once, however deep its own lane is and however long it has been waiting —
  a client that fills its quota cannot drain its own lane ahead of others. A queued client
  waits behind at most `clients-with-queued-work - 1` dispatches, never behind a lane's depth,
  and the bound does not depend on per-client service history: nothing is accumulated across
  rounds, so a client served heavily in earlier rounds starts each new round on equal footing.
  The set has exactly one reset, the round close, and a round closes both when every queued
  client has been served and when the ring runs empty (nothing is queued at all); because that
  second close fires as soon as the lanes drain, a long job holding a worker busy cannot
  accumulate one retained key per dispatch. A ring that never empties (one client keeps a lane
  non-empty while new clients stream in) is capped in the same spirit: once the set reaches
  `worker-count + queue-capacity` keys the round is closed too, restarting selection at the ring
  head — the longest-waiting client — so the extra dispatch a served client may get there never
  overtakes a client that has been queued longer. The set therefore never holds more keys than
  there are units of global capacity, whatever the client population or churn. A lane
  that re-enqueues work it already had (retry, duplicate) does not become eligible a second
  time in the same round, so it cannot jump ahead either. One lane draining completely at a
  time still happens only when all other lanes are empty.
- **Global bounds unchanged.** The dispatcher admits nothing while `running + in-flight
  lanes == worker-count + queue-capacity`; nothing is pre-admitted, and the executor's
  `AbortPolicy` remains only a backstop — the capacity check itself is what rejects.

Queue-full rollback is unchanged in shape: a submission that cannot hold a slot throws
`JobQueueFullException` (`429 JOB_QUEUE_FULL`), and the rejection is one atomic no-op to
world state — the job row and its idempotency mapping are removed, the attempt correlation
and client-quota reservation are released exactly once, and the rejected idempotency key
can be reused immediately. Removing a queued job (cancellation, deadline expiry, eviction)
releases its one global slot exactly once; a job removed from its lane is never executed.

Retries and duplicates stay fair and single-counted: a retried `FAILED` attempt re-enters
the **submitting client's own lane at its tail** (it never jumps ahead of other clients'
queued work and never escapes to another quota bucket), and a duplicate idempotency key
returns the existing snapshot without consuming a second slot or a second lane entry.

Termination follows the same gate. The drain closes the dispatcher first — it sets the stop
flag and drops every pending lane entry — so no attempt queued in a lane is ever executed
afterwards; the sweep fails those `QUEUED` jobs with `JOB_SHUTDOWN` (attempt number and
checkpoint preserved for a retry). Only the attempts already handed off (at most
`worker-count`) get the grace window, and what is still running at its end is interrupted.
`submit` and `retry` after the drain are refused by the closed lanes through the very same
queue-full rollback described above (`429 JOB_QUEUE_FULL`, no residue), so the executor's
`RejectedExecutionException` stays an unreachable backstop rather than an error path.

The fairness state stays observable through the existing surface without new response fields:
the per-client counter map `activeJobsByClient` (in-flight slots held by every known client —
queued or running) already feeds the `429 JOB_QUOTA_EXCEEDED` details, the service tracks each
client's queued lane depth (`queuedJobsForClient`), and the `dispatchedInFlight` admission
counter serializes round-robin order in the dispatcher. No `BatchHashJobResponse` field or
controller endpoint changed.

#### G. Rate limiting interaction (explicit decision)

All six jobs endpoints live under `/api/v1/utils/**`, so they are **not exempt** from `RateLimitFilter`
(see [Rate Limiting](#-rate-limiting)): every `POST /api/v1/utils/jobs/hash`, every
`GET /api/v1/utils/jobs/{jobId}` poll, every `DELETE /api/v1/utils/jobs/{jobId}` cancellation, every
`POST /api/v1/utils/jobs/{jobId}/retry`, every `POST /api/v1/utils/jobs/{jobId}/heartbeat` renewal **and** every
`GET /api/v1/utils/jobs` listing consumes one slot of the
caller's window budget, because polling, cancelling, retrying, heartbeating or listing a job is a normal API
call. Being jobs,
they are **not** additionally exempted — no entry was added to `RateLimitFilter.shouldNotFilter` for the retry
route either. Consequences:

- A caller that submits a batch and then polls aggressively can exhaust its own budget and receive
  `429 RATE_LIMITED` (error `RATE_LIMITED`) *before* the job finishes. This is a client-throttling
  response, not a job failure: the job keeps running and is still retrievable once `Retry-After` elapses.
- The three `429` classes are distinguishable by the `error` field: `RATE_LIMITED` (too many calls —
  honour `Retry-After`) vs `QUEUE_FULL` (executor saturated — retry the **same** `idempotencyKey`, or repeat the
  same `POST …/retry`, since a rejected requeue leaves the job `FAILED` with its attempt count intact) vs
  `JOB_QUOTA_EXCEEDED` (you already hold `utiltools.jobs.max-active-jobs-per-client` non-terminal jobs — wait for
  one of yours to reach `COMPLETED`, `FAILED` or `CANCELLED`; the exhausted gauge is in `details.limit` /
  `details.activeJobs` and no job is registered by the rejected call).
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

#### H. List jobs (200 OK, bounded & newest first)

`GET /api/v1/utils/jobs` enumerates the jobs the in-memory registry currently holds, so a client that lost a
`jobId` — or an operator inspecting the live `QUEUED` / `RUNNING` workload — can recover without guessing ids.
Two optional query parameters bound the response:

| Parameter | Default | Bound | Behaviour |
| :-------- | :------ | :---- | :-------- |
| `status`  | *(none)* | `JobStatus` | Exact-match filter: `QUEUED`, `RUNNING`, `CANCELLING`, `COMPLETED`, `FAILED` or `CANCELLED`. Omitted ⇒ every status |
| `limit`   | `20` | clamped to `1..100` | Maximum number of entries returned; `0`, negative or `>100` values are clamped rather than rejected |

```bash
# newest 20 jobs, any status
curl http://localhost:8080/api/v1/utils/jobs

# only the jobs still waiting for a worker, capped at 5
curl 'http://localhost:8080/api/v1/utils/jobs?status=QUEUED&limit=5'
```

Each element is the **same point-in-time projection** as a poll response — `snapshot(job)` is reused — so
`secretKey` and the raw input items are never exposed, and the `RUNNING` lease fields (`startedAt`,
`executionLeaseExpiresAt`, `lastHeartbeatAt`) appear only while the job is `RUNNING`, exactly as on
`GET /api/v1/utils/jobs/{jobId}`. The array is ordered newest first by `createdAt` with `jobId` as a
deterministic tie-breaker, so two jobs created in the same instant always keep the same relative order.

```json
{
  "success": true,
  "message": "Job list retrieved",
  "data": [
    {
      "jobId": "9a1f0b7c-2f4d-4a51-8f0b-2c6d8e1a7b33",
      "status": "RUNNING",
      "itemCount": 2,
      "attempt": 1,
      "maxAttempts": 3,
      "attempts": [
        { "attempt": 1, "status": "RUNNING", "startedAt": "2026-11-07T13:41:02.113004Z",
          "auditEntryId": "0b7f4c33-51a2-4e0a-9c18-2f7d6a3e91b4", "resumedFrom": 0,
          "succeededItems": [0], "failedItems": [] }
      ],
      "completedItems": 1,
      "succeededItems": [0],
      "failedItems": [],
      "createdAt": "2026-11-07T13:41:02.108621Z",
      "startedAt": "2026-11-07T13:41:02.113004Z",
      "executionLeaseExpiresAt": "2026-11-07T13:41:32.113004Z",
      "lastHeartbeatAt": "2026-11-07T13:41:02.113004Z"
    },
    {
      "jobId": "1c7e5d40-6b29-4f88-9d0e-77ab1f3c5e88",
      "idempotencyKey": "batch-2026-11-07-002",
      "status": "COMPLETED",
      "itemCount": 1,
      "results": [
        { "algorithm": "SHA-512", "digest": "a5e019350fd755cb21a23f61f05f85b4e55aa6acc987436084fed97db11da5ad", "isHmac": false }
      ],
      "attempt": 1,
      "maxAttempts": 3,
      "attempts": [
        { "attempt": 1, "status": "COMPLETED", "startedAt": "2026-11-07T13:40:58.004117Z",
          "completedAt": "2026-11-07T13:40:58.006902Z",
          "auditEntryId": "8e2a6b15-3c47-4f90-b2d5-7a1e9c40f682", "resumedFrom": 0,
          "succeededItems": [0], "failedItems": [] }
      ],
      "completedItems": 1,
      "succeededItems": [0],
      "failedItems": [],
      "createdAt": "2026-11-07T13:40:58.001550Z",
      "startedAt": "2026-11-07T13:40:58.004117Z",
      "completedAt": "2026-11-07T13:40:58.006902Z"
    }
  ],
  "timestamp": "2026-11-07T13:41:03.500210Z"
}
```

The endpoint is **read-only and total**: it never mutates a job, never reorders or evicts the registry, and
returns **`200` with `"data": []`** when nothing matches — an empty registry is an empty success, never a `404`.
`status` is bound to the `JobStatus` enum, so an unknown value such as `?status=BOGUS` is a **`400`**
(`error: BAD_REQUEST`, message `Invalid value 'BOGUS' for parameter 'status'`) raised by the existing
`MethodArgumentTypeMismatchException` handler before the service is called — the same contract as any other
enum-typed query parameter in this API.

As with polling, listing reflects only what the registry still holds: `COMPLETED`, `FAILED` and `CANCELLED`
jobs disappear once `utiltools.jobs.retention-seconds` elapses, so the list is a live view, not a history log.
It is deliberately bounded (default `20`, ceiling `100`) and not paginated — for the full history use
`GET /api/v1/utils/audit/entries`.

---

## 🚦 Rate Limiting

Every `/api/v1/utils/**` request passes through `RateLimitFilter` before Spring MVC. A
`SlidingWindowRateLimiter` keeps a fixed-size per-client ring buffer (`long[requests-per-window]`) of
admitted timestamps, each paired with the weighted cost it charged, and admits a call only while the
sum of the costs still inside the trailing `window-ms` window plus the request's own cost stays
within `requests-per-window`. Admission is atomic per client, so concurrent requests can never
exceed the budget, whichever costs they carry.

Admission is weighted rather than unit-valued: `RouteCostResolver` maps the request method plus URI
to a positive integer cost, resolved **once** per request, so the endpoints that buy disproportionate
worker or CPU capacity are charged accordingly.

| Route | Cost |
| :--- | :--- |
| `POST /api/v1/utils/audit/verify` | `5` (`utiltools.ratelimit.costs.audit-verify`) |
| `POST /api/v1/utils/jobs/hash` | `3` (`utiltools.ratelimit.costs.job-submit`) |
| `POST /api/v1/utils/json/query` | `2` (`utiltools.ratelimit.costs.json-query`) |
| every other route (job polling, retry, cancel, audit reads, …) | `1` (`utiltools.ratelimit.costs.default`) |

The approximate spelling of a costed route is normalised before the lookup (percent-decoded, matrix
parameters and query string stripped, one trailing slash dropped), so a costed route cannot be
reached for the default cost by writing it differently.

Rejected requests receive HTTP `429` with the unified envelope and never reach a controller:

```json
{
  "success": false,
  "message": "Rate limit exceeded",
  "error": "RATE_LIMITED",
  "details": { "cost": 5, "retryAfterSeconds": 42, "limit": 60, "windowMs": 60000 },
  "timestamp": "2026-10-31T00:50:00Z"
}
```

Response headers on every `/api/v1/utils/*` call (allowed or rejected):

| Header | Meaning |
| :--- | :--- |
| `Retry-After` | Seconds until a slot frees (rejections only) |
| `X-RateLimit-Limit` | Configured `requests-per-window` (the weighted budget) |
| `X-RateLimit-Remaining` | Weighted units left to this client in the current window (`0` on denial) |
| `X-RateLimit-Reset` | Seconds until the oldest admitted event leaves the window |
| `X-RateLimit-Cost` | Weighted units this request charged (admitted and rejected) |

A rejected request consumes no capacity, so a retry when `Retry-After` elapses succeeds. Idle
client buckets are evicted by a `@Scheduled` cleanup pass, and `max-tracked-clients` bounds the map:
the oldest idle bucket is dropped first, and once every tracked bucket is active an unseen client is
rejected with the same `429 RATE_LIMITED` response instead of being inserted.

`GET /api/v1/utils/rate-limit/status` reports the same weighted `limit`/`remaining`/`resetAt` values
the filter would use, but its peek path never inserts a bucket and never consumes a second
admission: the response body's `remaining` is therefore the value after the request's own charge.

### Configuration (`application.properties`)

| Property | Default | Purpose |
| :--- | :--- | :--- |
| `utiltools.ratelimit.enabled` | `true` | Master switch; `false` passes everything through with no headers |
| `utiltools.ratelimit.requests-per-window` | `60` | Admitted requests per client per window |
| `utiltools.ratelimit.window-ms` | `60000` | Sliding window length in milliseconds |
| `utiltools.ratelimit.cleanup-interval-ms` | `30000` | Fixed delay between idle-bucket cleanup passes |
| `utiltools.ratelimit.max-tracked-clients` | `10000` | Hard cap on tracked client keys (bounds memory) |
| `utiltools.ratelimit.trust-forwarded-header` | `false` | Use the first well-formed `X-Forwarded-For` entry as the client key |
| `utiltools.ratelimit.costs.default` | `1` | Weighted cost of any route without a specific entry (must be `>= 1`) |
| `utiltools.ratelimit.costs.audit-verify` | `5` | Weighted cost of `POST /api/v1/utils/audit/verify` |
| `utiltools.ratelimit.costs.job-submit` | `3` | Weighted cost of `POST /api/v1/utils/jobs/hash` |
| `utiltools.ratelimit.costs.json-query` | `2` | Weighted cost of `POST /api/v1/utils/json/query` |

Setting all four `utiltools.ratelimit.costs.*` values to `1` restores unit-valued accounting.

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
| `400 BAD_REQUEST` | `BAD_REQUEST` | Invalid timezone ID, unsupported hash algorithm, corrupt Base64, malformed or non-UTF-8 percent escape on `crypto/url`, malformed compact JWT (segment count, base64url, UTF-8, JSON shape, `alg`/`typ`) on `crypto/jwt/decode` |
| `400 BAD_REQUEST` | `BAD_REQUEST` | Malformed HTTP request body |
| `404 NOT_FOUND` | `JOB_NOT_FOUND` | Unknown or evicted batch job id |
| `404 NOT_FOUND` | `NOT_FOUND` | Unknown audit journal entry id |
| `409 CONFLICT` | `REPLAY_CONFLICT` | Replay of a FAILED, non-deterministic, oversized, or still-PENDING entry, or a replay of an entry whose claim is already `IN_PROGRESS` (live lease) or was lost to a lease takeover |
| `409 CONFLICT` | `JOB_RETRY_CONFLICT` | Retry of a `QUEUED`/`RUNNING`/`CANCELLING`/`COMPLETED`/`CANCELLED` batch job, or of a `FAILED` job whose `attempt` already equals `maxAttempts` |
| `409 CONFLICT` | `IDEMPOTENCY_CONFLICT` | Batch job idempotency key reused with a different request payload |
| `409 CONFLICT` | `JOB_CANCELLATION_CONFLICT` | Cancellation attempted on a batch job that is `COMPLETED` or `FAILED` (a `QUEUED` or `RUNNING` job is cancelled: `CANCELLED` or `CANCELLING`) |
| `429 TOO_MANY_REQUESTS` | `QUEUE_FULL` | Batch job queue is saturated; retry after capacity frees |
| `429 TOO_MANY_REQUESTS` | `JOB_QUOTA_EXCEEDED` | Client holds `utiltools.jobs.max-active-jobs-per-client` non-terminal batch jobs; `details` carries `limit` and `activeJobs` |
| `202`, then `FAILED` on poll (`data.error`) | `BATCH_ITEM_HASH_FAILED` | One or more items threw while hashing and not every item ended up durably hashed; the remaining items were still hashed, and `succeededItems` / `failedItems` carry the per-item outcome |
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
- **`CryptoServiceTest`**: Standard hashing vectors, HMAC calculations, Base64 encode/decode, URL percent-encoding across `COMPONENT`/`PATH`/`FORM` (round-trip, mode-specific `+`, malformed escape and undecodable UTF-8 rejection), UUID v4 generation, UUIDv7 fixed-clock vector, UUIDv8 fixed-clock vector and counter-modulo-65536 masking, `version=V4|V7|V8` parsing, clock-regression monotonicity, and an 8-thread x 10000 duplicate-free concurrency invariant.
- **`CryptoControllerTest`**: HTTP validation and crypto response envelope testing, including `crypto/url` encode/decode, decode-only `crypto/jwt/decode` (`algorithm`, `payload`, `signatureBytes`, `verified=false`, no raw token or signature echoed), 400 `BAD_REQUEST` vs `VALIDATION_ERROR`, and `CRYPTO_URL` / `CRYPTO_JWT_DECODE` journal/replay (`VERIFIED`), including `version=V4|V7|V8` selection, compact UUIDv7 and UUIDv8, `CRYPTO_UUID` journal/replay for V8, and the 400 unified envelope for an unknown `version`.
- **`JsonServiceTest`**: Formatting, minification, Jackson parsing error positions, JSONPath queries, six-op patch success, rollback to byte-identical original, missing paths, malformed pointers, `-` append, dry runs, input-not-mutated.
- **`JsonControllerTest`**: JSON endpoints MockMvc test suite, including the 200 patch envelope, the 422 `PATCH_ROLLBACK` audit payload, empty-`operations` validation rejection, and the stale-fallback envelopes: an opt-in request served from the cache with `staleFallback`/`cachedAt`/`cacheAgeMillis` on an `OPEN` breaker, plus the unchanged `503` `CIRCUIT_OPEN` envelope for a miss, a different `jsonPath` and a non-opt-in caller.
- **`TextServiceTest`**: Every operation (`UPPER`, `LOWER`, `TITLE`, `CAMEL`, `SNAKE`, `KEBAB`, `SLUG`, `SENTENCE_CASE`), sentence-case idempotence and `[.!?]`-run boundaries, diacritic slugging over NFD, emoji/surrogate-pair and multiline statistics, blank-text constraint violations, unknown-operation rejection, and `changed`/`length` measured in code points; plus replace coverage: `$n` group expansion, literal metacharacters, `all=false` first-match-only, case-insensitive matching, no-match results, invalid-regex rejection, code-point `length`, every request constraint bound, and the 1000000-character result bound.
- **`TextControllerTest`**: Text endpoints MockMvc suite, including the snake-case and sentence-case transform envelopes, diacritic slugging, `400 VALIDATION_ERROR` for an unknown operation, `400 VALIDATION_ERROR` for blank text, and a `TEXT_TRANSFORM` entry listed by `/audit/entries` and replayed as `VERIFIED`; a replace envelope with `$n` groups, `400 BAD_REQUEST` for an invalid regex (journalled `TEXT_REPLACE`/`FAILED`), `400 VALIDATION_ERROR` for blank text, an absent `replacement` and an oversized `pattern`, and a `TEXT_REPLACE` entry listed by `/audit/entries` and replayed as `VERIFIED`.
- **`BatchHashJobServiceTest`**: Concurrent same-key deduplication, key/payload fingerprint conflict detection (changed items, reordered items, changed algorithm or `secretKey`), 8-thread different-payload races, identical-key/identical-payload retries that mint no second execution, queue-saturation rollback and retry, result ordering, worker-failure terminal state, eviction safety, queued cancellation (single transition, no hashing afterwards, idempotent repeat), `RUNNING`/terminal cancellation conflict, and `CANCELLED` eviction only after the retention cutoff. Retry coverage: transient failure then a successful retry (`attempt` 2, attempt-1 `FAILED` entry retained), `RUNNING`/`COMPLETED`/`CANCELLED` conflicts that leave the snapshot unchanged, attempt exhaustion at `maxAttempts`, stale-worker fencing (a superseded generation writes nothing after a retry), and queue-rejection rollback that leaves the job `FAILED` with an unconsumed attempt while a later retry succeeds. Checkpoint coverage: a transient failure at a chosen item reports `completedItems` equal to the hashes that succeeded and leaves `results` absent; the retry rehashes only the suffix (per-item invocation counts) and publishes the full batch in input order with `resumedFrom` on the second attempt; per-item isolation coverage: one item's hash throwing keeps the batch going (later items still hashed, `failedItems` on the snapshot and the attempt row, `itemFailures` in the attempt's `BATCH_HASH` entry), every item throwing still ends `FAILED` with `BATCH_ITEM_HASH_FAILED`, a retry hashes **only** the unprocessed indices while a resumed attempt reports job-wide outcomes including the inherited prefix, and lease renewal plus heartbeat stay live across item failures; a stale worker from a reaped or superseded generation cannot advance or overwrite the checkpoint (replaying `executeAttempt` leaves the snapshot identical); a `429 QUEUE_FULL` retry restores the exact pre-retry `FAILED` snapshot including attempt, `completedItems` and the checkpoint; the journal descriptor and outcome carry `resumedFrom`/`completedItems` but never items or `secretKey`; and a cancelled job exposes no checkpoint results.
  Heartbeat coverage: a `RUNNING` job with a live lease extends `executionLeaseExpiresAt` to `Instant.now(clock) + executionLeaseSeconds` and stamps `lastHeartbeatAt` with the same instant; an unknown `jobId` is `404 JOB_NOT_FOUND`, a `QUEUED`/`CANCELLING`/`COMPLETED`/`CANCELLED` job is `409 JOB_RETRY_CONFLICT`, and an already-expired lease is `400 BAD_REQUEST` without reviving it; a heartbeat from a superseded attempt generation writes neither the lease nor a journal row; the success writes exactly one `HEARTBEAT` row with the bounded detail `lease renewed via heartbeat`; and the entry's `status`, `results`, `error`, `completedAt`, `startedAt`, `checkpoint` and `attemptHistory` are byte-identical afterwards.
- **`BatchJobControllerTest`**: Job endpoints MockMvc suite (202 submit/retry, 400, 404 `JOB_NOT_FOUND`, 409 `IDEMPOTENCY_CONFLICT`, 429 `QUEUE_FULL`, `DELETE` 200 `CANCELLED`, 404 `JOB_NOT_FOUND`, 409 `JOB_CANCELLATION_CONFLICT`, and `POST …/retry` 202 `QUEUED` vs 409 `JOB_RETRY_CONFLICT` with `jobId`/`status`/`attempt`/`maxAttempts` details, plus the additive `completedItems` on a `FAILED` snapshot beside every pre-existing field, and `POST …/heartbeat` returning the `200` success envelope with the renewed `executionLeaseExpiresAt`/`lastHeartbeatAt` beside `404 JOB_NOT_FOUND`, `409 JOB_RETRY_CONFLICT` and `400 BAD_REQUEST` error envelopes for each failure case).
- **`GlobalExceptionHandlerTest`**: Centralized error mapping and constraint validation verification.
- **`SlidingWindowRateLimiterTest`**: Exact-cap concurrent admission, deterministic window rollover on a mutable `Clock`, denials consuming no capacity, per-client isolation, idle eviction, and cap enforcement under many spoofed keys.
- **`RateLimitFilterTest`**: MockMvc proof of the `429 RATE_LIMITED` envelope, `Retry-After`/`X-RateLimit-*` headers, and that a rejected call never invokes the controller.
- **`RateLimitConfigTest`**: Property binding and overrides, filter registration for `/api/v1/utils/*` ahead of Spring MVC, and the `@Scheduled` cleanup wiring.
- **`CircuitBreakerTest`**: CLOSED→OPEN thresholds, forced `probe()` semantics, and the `HALF_OPEN` probe lease — a concurrent caller inside the lease gets `CIRCUIT_OPEN` "probe already in flight" with no audit row, a clock advance past the lease admits a new probe with exactly one `PROBE_LEASE_EXPIRED` row, late success and late failure from the superseded generation change no counter/state and append no row, and `reapExpiredProbe()` is idempotent.
- **`ResilienceControllerTest`**: MockMvc coverage of `GET /api/v1/utils/resilience` (`probeInFlight` / `probeLeaseExpiresAt` in the payload) and of `POST /api/v1/utils/resilience/probe` with the unified envelope.
- **`AuditTrailTest`**: Bounded audit ring buffer ordering and capacity-eviction behaviour.
- **`JsonQueryCircuitOpenTest`**: End-to-end proof that an `OPEN` breaker maps to a `503` `CIRCUIT_OPEN` envelope on `/api/v1/utils/json/query`.
- **`JsonQueryFallbackCacheTest`**: Clock-driven unit coverage of the last-known-good cache — key normalization and SHA-256 collision-free separation of `json`/`jsonPath` pairs, `remember`/`serve` round-trip, TTL expiry on a mutable `Clock`, oldest-first eviction under `max-entries`, the scheduled sweep, and the bounded hit/miss/expired/eviction counters.
- **`JsonQueryStaleFallbackTest`**: Service-level fallback proof — a warmed cache plus an `OPEN` breaker returns the cached result with `staleFallback=true`, the original `cachedAt` and a correct `cacheAgeMillis` while breaker state, counters and `resetAt` stay byte-identical; a miss, an expired TTL entry, a different `jsonPath` and `allowStaleFallback=false` all still raise the original `CIRCUIT_OPEN`; a live failure keeps the previous cached value; a fallback is never re-inserted; and an opt-in call appends a non-deterministic `JSON_QUERY_FALLBACK` entry that is rejected for replay with `NON_DETERMINISTIC`.
- **`UtiltoolsApiApplicationTests`**: Spring ApplicationContext loading verification.

---

## 🔎 Configuration Check (`python3 cli.py check`)

The repository ships a small Python maintainer toolkit under `src/ai_maintainer/`. Before running a
maintenance cycle, pre-flight the resolved `MaintainerConfig` with the read-only `check` command:

```bash
python3 cli.py check
```

It prints one row per check to stdout, sends any failure lines to stderr, and never writes state,
enqueues a task or starts the engine loop. `--maintainer-root PATH` and `--config PATH` override
the root and the `config.json` that are validated.

```text
[PASS] paths.maintainer_root       /home/me/ai_team
[SKIP] paths.ai_team_root          not configured
[SKIP] paths.default_project_root  not configured
[PASS] config.consistency          configuration layers load cleanly
[PASS] models.primary_model        primary_model: free for cli=pi (llama3:free)
[SKIP] models.secondary_model      secondary_model: cli not pi (unset), tier not checked
[WARN] framework.orchestrator      unknown (no FrameworkWatcher and no active_task directory)
[INFO] framework.queue             unknown (n/a queued, n/a active, last completed none)
check: 3 passed, 0 failed, 3 skipped, 1 warnings
```

**Exit codes:** `0` — every check passed (warnings and skips are fine); `1` — at least one `FAIL`
row; `2` — argparse usage error (unknown flag / subcommand).

**Check ids:** `paths.maintainer_root`, `paths.ai_team_root`, `paths.default_project_root`
(must be existing directories, unset optional paths are skipped), `config.consistency`
(the `MaintainerConfig.load` layers — built-in defaults → optional `must_config.py` →
`config.json` → `MAINTAINER_*` environment variables → keyword overrides — must load), `models.primary_model`,
`models.secondary_model` (must be free tier when their CLI is `pi`), `framework.orchestrator`
(orchestrator up/down from `FrameworkWatcher.get_status()` or the `active_task` heartbeat) and
`framework.queue` (queue state, active task count, last completed task).

Rows are `PASS`, `FAIL`, `SKIP`, `WARN` or `INFO`. Configure the paths with the environment
variables `MAINTAINER_ROOT`, `MAINTAINER_AI_TEAM_ROOT` and `MAINTAINER_DEFAULT_PROJECT_ROOT` (or
the `--maintainer-root` flag for the root being validated). The three path fields are **not**
`MaintainerConfig` fields, so they cannot be set from `config.json`: that file only accepts
`MaintainerConfig` keys (`primary_cli`, `primary_model`, `secondary_cli`, `secondary_model` and the
tuning values), and any other key makes the `config.consistency` row fail with
`unknown config key(s) in config.json: [...]`. There is no `.env` layer; `config.json` and the
`MAINTAINER_*` variables are the only configuration sources.

---

## 📊 Maintainer Status (`python3 cli.py status [--json]`)

`status` is the read-only reporter next to `check`. It summarises what the maintainer is doing —
daemon heartbeat, orchestrator, active task, queue, memory and the persisted counters — without
starting the engine, acquiring a lock, enqueuing work or writing any file:

```bash
python3 cli.py status          # human-readable summary
python3 cli.py status --json   # one JSON object on stdout, for tooling
```

```text
maintainer status
daemon: running (source=active_task, heartbeat_age=12.0s)
orchestrator: up
active task: UTILTOOLS-60
queue: busy (1 queued, 2 active, last completed UTILTOOLS-59)
memory: rss 15.0 MiB, peak 14.9 MiB
counters: total_cycles=12 bugs_patched=3 total_learnings=7
latest task run: UTILTOOLS-60 in_progress (age 42.0s)
unavailable: none
```

`--json` prints exactly one JSON object (single line, trailing newline) whose top-level keys are
`daemon_status`, `orchestrator_up`, `orchestrator_status`, `active_task_id`, `queue_state`,
`queue_depth`, `active_tasks`, `last_completed_task`, `heartbeat_age_sec`, `memory`,
`total_cycles`, `bugs_patched`, `total_learnings`, `latest_task_run`, `generated_at`, `sources`
and `unavailable`. Text mode renders those same fields, so the two modes never disagree. The
payload is a superset of the required core key set: `last_completed_task` and
`heartbeat_age_sec` are additional documented keys (INV-1 requires presence, not exclusivity).
Every number is finite, so the document parses with a strict JSON parser (`NaN`/`Infinity` are
dropped to `null` rather than emitted).

- `daemon_status` is `running` (heartbeat younger than 900 s), `stale` or `unknown`;
  `orchestrator_up` is `true`/`false`/`null` alongside the same value as `orchestrator_status`.
- Counters are read from the read-only document `<state_root>/maintainer_status.json`
  (`total_cycles`, `bugs_patched`, `total_learnings`, optional `latest_task_run`). The file is an
  input only: `status` never creates, refreshes or migrates it. Missing or malformed documents
  yield `null` values plus the field name in `unavailable`, never an error. Only a regular file is
  read (a FIFO, device or directory at that path is ignored) and a bounded read is parsed, so
  oversized, deeply nested or invalid documents degrade instead of raising.
- In text mode every document-supplied string is flattened to one printable line, so a task id
  cannot forge extra report lines.
- `memory` describes the process running `status` (`rss_kb`/`peak_rss_kb` from `/proc`, or
  `available: false`).
- `sources` says where each value came from (`FrameworkWatcher`, `active_task`, the counter
  document, `this_process`, `none`/`unavailable`), so a `null` is attributable.
- No config values, model ids or absolute paths appear in the output.

**Exit codes:** `0` — a report was rendered (this holds for `running` and `stale` alike, and for
configured-but-unreachable state); `2` — argparse usage error. `status` never exits `1`; the
verdict belongs to `check`. `--maintainer-root PATH` and `--config PATH` override the root and the
`config.json` that are read.
