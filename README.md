# Util Tools REST API Server

A high-performance, developer-focused utility REST API server built from scratch with **Java 21** and **Spring Boot 4**.

---

## 🚀 Key Features & 3 Core Utility Tools

The API server provides 3 of the most essential daily developer utilities via clean, predictable, and fully documented HTTP REST APIs:

### 1. 🕒 DateTime & Timezone Utility (`/api/v1/utils/datetime`)
- **Convert (`POST /api/v1/utils/datetime/convert`)**: Convert between epoch timestamp (seconds or milliseconds), ISO-8601 strings, and target IANA timezones (e.g. `Asia/Tokyo`, `America/New_York`, `UTC`) with optional custom formatting patterns.
- **Diff (`POST /api/v1/utils/datetime/diff`)**: Compute exact duration between two datetime points in days, hours, minutes, seconds, milliseconds, and human-readable text.
- **Current (`GET /api/v1/utils/datetime/current`)**: Fetch current server and global time with millisecond precision in any requested timezone.

### 2. 🔐 Cryptography & Encoding Utility (`/api/v1/utils/crypto`)
- **Hash & HMAC (`POST /api/v1/utils/crypto/hash`)**: Compute cryptographic digests (SHA-256, SHA-512, MD5, SHA-1) with optional HMAC secret keying.
- **Base64 (`POST /api/v1/utils/crypto/base64`)**: Encode and decode standard Base64 and URL-safe Base64 strings with full UTF-8 support.
- **UUID Generator (`GET /api/v1/utils/crypto/uuid`)**: Generate standard UUIDs (v4) with customizable batch count (1-100) and compact (32-hex) format options.

### 3. 📄 JSON & Data Processor Utility (`/api/v1/utils/json`)
- **Format & Minify (`POST /api/v1/utils/json/format`)**: Pretty-print or minify JSON payloads.
- **Validate (`POST /api/v1/utils/json/validate`)**: Validate JSON syntax, reporting exact line and column numbers on syntax errors.
- **Query (`POST /api/v1/utils/json/query`)**: Query and extract nested elements using JSONPath expressions (e.g., `$.store.books[*].title`).

---

## 🛠 Tech Stack

- **Language**: Java 21 (Records, Pattern Matching, Modern `java.time`, Modern Stream APIs)
- **Framework**: Spring Boot 4 (`spring-boot-starter-web`, `spring-boot-starter-validation`)
- **JSON Engine**: Jackson (`ObjectMapper`) & Jayway JsonPath
- **Documentation**: OpenAPI 3.0 & Swagger UI (`springdoc-openapi-starter-webmvc-ui`)
- **Testing**: JUnit 5, MockMvc, AssertJ (100% test pass rate across 32 unit and integration tests)

---

## ⚡ Quick Start

### Prerequisites
- JDK 21+
- Maven 3.8+ (or use the included `./mvnw` wrapper)

### Build & Test
```bash
./mvnw clean test
```

### Package Application
```bash
./mvnw clean package
```

### Run Server
```bash
# Using Maven Wrapper
./mvnw spring-boot:run

# Or run packaged JAR
java -jar target/utiltools-api-0.0.1-SNAPSHOT.jar
```

The server starts by default on port `8080`.

---

## 📖 Interactive API Documentation (Swagger UI)

When the server is running, navigate to:
- **Swagger UI**: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- **OpenAPI 3 JSON Specification**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

---

## 📡 API Usage & Curl Examples

### Standard Response Envelope
All API endpoints return a uniform response envelope:
```json
{
  "success": true,
  "message": "Operation successful",
  "data": { ... },
  "timestamp": "2026-10-31T00:50:38Z"
}
```

---

### 1. DateTime Utility

#### Convert Epoch Milliseconds to Tokyo Time
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
  }
}
```

#### Calculate Difference Between Two Datetimes
```bash
curl -X POST http://localhost:8080/api/v1/utils/datetime/diff \
  -H "Content-Type: application/json" \
  -d '{
    "start": "2026-01-01T00:00:00Z",
    "end": "2026-01-03T12:30:00Z"
  }'
```

#### Get Current Time
```bash
curl "http://localhost:8080/api/v1/utils/datetime/current?timeZone=Asia/Taipei"
```

---

### 2. Crypto & Encoding Utility

#### SHA-256 Digest
```bash
curl -X POST http://localhost:8080/api/v1/utils/crypto/hash \
  -H "Content-Type: application/json" \
  -d '{
    "algorithm": "SHA-256",
    "content": "Hello World"
  }'
```

#### HMAC-SHA256 Signature
```bash
curl -X POST http://localhost:8080/api/v1/utils/crypto/hash \
  -H "Content-Type: application/json" \
  -d '{
    "algorithm": "SHA-256",
    "content": "Hello World",
    "secretKey": "my-secret-key"
  }'
```

#### Base64 Encode
```bash
curl -X POST http://localhost:8080/api/v1/utils/crypto/base64 \
  -H "Content-Type: application/json" \
  -d '{
    "action": "ENCODE",
    "content": "Hello World"
  }'
```

#### Generate Batch UUIDs
```bash
curl "http://localhost:8080/api/v1/utils/crypto/uuid?count=3&compact=true"
```

---

### 3. JSON & Data Processor Utility

#### Format / Minify JSON
```bash
curl -X POST http://localhost:8080/api/v1/utils/json/format \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"user\":{\"id\":101,\"name\":\"Alice\"}}",
    "minify": false
  }'
```

#### Validate JSON Syntax
```bash
curl -X POST http://localhost:8080/api/v1/utils/json/validate \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"user\": 123"
  }'
```

#### Query with JSONPath
```bash
curl -X POST http://localhost:8080/api/v1/utils/json/query \
  -H "Content-Type: application/json" \
  -d '{
    "json": "{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}",
    "jsonPath": "$.users[*].name"
  }'
```

---

## 🛡 Global Error Handling

Invalid requests and syntax errors are captured by `@RestControllerAdvice` and returned as standard error payloads with HTTP 400:
```json
{
  "success": false,
  "message": "Validation failed",
  "error": "VALIDATION_ERROR",
  "details": [
    "algorithm is required (e.g., SHA-256, SHA-512, MD5)"
  ],
  "timestamp": "2026-10-31T00:50:00Z"
}
```
