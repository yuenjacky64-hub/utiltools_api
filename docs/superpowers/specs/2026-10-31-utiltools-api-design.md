# Design Specification: Java 21 & Spring Boot 4 Util Tools API Server

## 1. Overview
The **Util Tools API Server** is a lightweight, high-performance RESTful utility service built from scratch using **Java 21** and **Spring Boot 4**. It provides developers, automated pipelines, and client applications with 3 of the most essential daily developer utilities via clean, predictable, and fully documented HTTP APIs.

## 2. Selected Utility Tools
We selected three distinct, high-frequency developer utilities that are ideally suited for an API service:

1. **DateTime & Timezone Engine (`/api/v1/utils/datetime`)**:
   - Universal developer pain point across distributed systems and microservices.
   - Operations:
     - `POST /api/v1/utils/datetime/convert`: Convert between epoch timestamp (seconds/millis), ISO-8601 string, and target IANA timezones.
     - `POST /api/v1/utils/datetime/diff`: Calculate duration between two timestamps (days, hours, minutes, seconds, human-readable summary).
     - `GET /api/v1/utils/datetime/current`: Get current timestamp in UTC and specified timezone(s).

2. **Crypto & Encoding Engine (`/api/v1/utils/crypto`)**:
   - Essential for security operations, signature checks, token debugging, and data transmission.
   - Operations:
     - `POST /api/v1/utils/crypto/hash`: Compute cryptographic digests (SHA-256, SHA-512, MD5) with optional HMAC secret key.
     - `POST /api/v1/utils/crypto/base64`: Encode and decode Base64 and URL-safe Base64 text.
     - `GET /api/v1/utils/crypto/uuid`: Generate random UUID v4 with customizable count and format (standard hyphenated or compact).

3. **JSON & Data Processor (`/api/v1/utils/json`)**:
   - Vital for API debugging, log processing, and webhook payload inspection.
   - Operations:
     - `POST /api/v1/utils/json/format`: Format (pretty-print) or minify raw JSON strings.
     - `POST /api/v1/utils/json/validate`: Validate JSON syntax, reporting exact error line/column and diagnostic message if invalid.
     - `POST /api/v1/utils/json/query`: Extract matching nodes using JSONPath expressions (e.g., `$.items[*].id`).

---

## 3. Architecture & Tech Stack

- **Language**: Java 21 (Records, Pattern Matching, Modern `java.time`, Modern Stream APIs)
- **Framework**: Spring Boot 4 (`4.1.2.RELEASE` / `spring-boot-starter-webmvc`, `spring-boot-starter-validation`)
- **JSON & Query Processing**: Jackson (`ObjectMapper`), Jayway JsonPath
- **Documentation**: OpenAPI 3.0 / Swagger UI (`springdoc-openapi-starter-webmvc-ui`)
- **Testing**: JUnit 5, MockMvc, AssertJ

### Package Structure
```
com.example.utiltools/
├── UtilToolsApplication.java
├── common/
│   ├── ApiResponse.java
│   ├── ErrorResponse.java
│   └── GlobalExceptionHandler.java
├── datetime/
│   ├── controller/DateTimeController.java
│   ├── service/DateTimeService.java
│   └── dto/
│       ├── DateTimeConvertRequest.java
│       ├── DateTimeConvertResponse.java
│       ├── DateTimeDiffRequest.java
│       ├── DateTimeDiffResponse.java
│       └── CurrentTimeResponse.java
├── crypto/
│   ├── controller/CryptoController.java
│   ├── service/CryptoService.java
│   └── dto/
│       ├── HashRequest.java
│       ├── HashResponse.java
│       ├── Base64Request.java
│       ├── Base64Response.java
│       └── UuidResponse.java
└── json/
    ├── controller/JsonController.java
    ├── service/JsonService.java
    └── dto/
        ├── JsonFormatRequest.java
        ├── JsonFormatResponse.java
        ├── JsonValidateRequest.java
        ├── JsonValidateResponse.java
        ├── JsonQueryRequest.java
        └── JsonQueryResponse.java
```

---

## 4. API Specification & Data Models

### 4.1 Unified Response Envelope
Every endpoint returns a consistent JSON envelope:
```json
{
  "success": true,
  "message": "Operation successful",
  "data": { ... },
  "timestamp": "2026-10-31T00:35:00Z"
}
```

In case of error (4xx / 5xx):
```json
{
  "success": false,
  "message": "Invalid JSON syntax at line 2, column 5",
  "error": "BAD_REQUEST",
  "details": ["..."],
  "timestamp": "2026-10-31T00:35:00Z"
}
```

### 4.2 Endpoint Details

#### DateTime Utility
1. `POST /api/v1/utils/datetime/convert`
   - Request:
     ```json
     {
       "input": "1730335200000", 
       "inputFormat": "EPOCH_MILLIS", 
       "targetTimeZone": "Asia/Tokyo"
     }
     ```
   - Response:
     ```json
     {
       "epochMillis": 1730335200000,
       "epochSeconds": 1730335200,
       "isoUtc": "2024-10-31T00:40:00Z",
       "formattedTarget": "2024-10-31T09:40:00+09:00",
       "targetTimeZone": "Asia/Tokyo"
     }
     ```
2. `POST /api/v1/utils/datetime/diff`
   - Request:
     ```json
     {
       "start": "2026-01-01T00:00:00Z",
       "end": "2026-01-03T12:30:00Z"
     }
     ```
   - Response:
     ```json
     {
       "days": 2,
       "hours": 60,
       "minutes": 3630,
       "seconds": 217800,
       "humanReadable": "2 days 12 hours 30 minutes 0 seconds"
     }
     ```
3. `GET /api/v1/utils/datetime/current?timeZone=America/New_York`
   - Response:
     ```json
     {
       "epochMillis": 1793407200000,
       "epochSeconds": 1793407200,
       "utc": "2026-10-31T00:40:00Z",
       "local": "2026-10-30T20:40:00-04:00",
       "timeZone": "America/New_York"
     }
     ```

#### Crypto & Encoding Utility
1. `POST /api/v1/utils/crypto/hash`
   - Request:
     ```json
     {
       "algorithm": "SHA-256", 
       "content": "Hello World", 
       "secretKey": null
     }
     ```
   - Response:
     ```json
     {
       "algorithm": "SHA-256",
       "digest": "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e"
     }
     ```
2. `POST /api/v1/utils/crypto/base64`
   - Request:
     ```json
     {
       "action": "ENCODE", 
       "content": "Hello World", 
       "urlSafe": false
     }
     ```
   - Response:
     ```json
     {
       "action": "ENCODE",
       "result": "SGVsbG8gV29ybGQ="
     }
     ```
3. `GET /api/v1/utils/crypto/uuid?count=3&compact=false`
   - Response:
     ```json
     {
       "count": 3,
       "uuids": [
         "e58ed763-928c-4155-bee9-fdbaaadc15f3",
         "8f288d44-0c21-4f9e-a611-3008928ee55b",
         "2c8fb995-1f95-46bd-8951-6f0ce3a88c22"
       ]
     }
     ```

#### JSON & Data Processor Utility
1. `POST /api/v1/utils/json/format`
   - Request:
     ```json
     {
       "json": "{\"name\":\"Alice\",\"age\":30}", 
       "minify": false, 
       "indent": 2
     }
     ```
   - Response:
     ```json
     {
       "formattedJson": "{\n  \"name\" : \"Alice\",\n  \"age\" : 30\n}"
     }
     ```
2. `POST /api/v1/utils/json/validate`
   - Request:
     ```json
     {
       "json": "{\"invalid\": [}"
     }
     ```
   - Response:
     ```json
     {
       "valid": false,
       "error": "Unexpected close marker '}': expected ']' at line 1, column 15",
       "line": 1,
       "column": 15
     }
     ```
3. `POST /api/v1/utils/json/query`
   - Request:
     ```json
     {
       "json": "{\"store\":{\"book\":[{\"title\":\"Sayings of the Century\",\"price\":8.95},{\"title\":\"Sword of Honour\",\"price\":12.99}]}}",
       "jsonPath": "$.store.book[*].title"
     }
     ```
   - Response:
     ```json
     {
       "jsonPath": "$.store.book[*].title",
       "result": [
         "Sayings of the Century",
         "Sword of Honour"
       ]
     }
     ```

---

## 5. Error Handling & Validation
- Jakarta Validation annotations (`@NotBlank`, `@NotNull`, `@Min`, `@Max`, `@Pattern`).
- MethodArgumentNotValidException caught by `@RestControllerAdvice` returning field errors.
- Business exception handling for invalid timezones (`ZoneRulesException`), unsupported algorithms (`NoSuchAlgorithmException`), corrupted Base64 (`IllegalArgumentException`), and JSON syntax errors (`JsonProcessingException`).

---

## 6. Testing Strategy
- Unit tests for services:
  - `DateTimeServiceTest`: Timezone shifts, epoch conversions, edge-case durations.
  - `CryptoServiceTest`: Known vectors for SHA-256 / MD5 / HMAC, Base64 roundtrip, UUID format verification.
  - `JsonServiceTest`: Valid vs malformed JSON, pretty-printing, complex JSONPath queries.
- MockMvc Integration tests:
  - `DateTimeControllerTest`: HTTP endpoint testing with JSON payloads and error cases.
  - `CryptoControllerTest`: HTTP endpoint testing for hashing and UUID generation.
  - `JsonControllerTest`: HTTP endpoint testing for formatting, validation, and JSONPath extraction.
- End-to-end verification:
  - Application startup check and curl tests against running server.
