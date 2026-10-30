# Util Tools API Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (Inline Execution with checkpoints). Steps use checkbox (`- [ ]`) syntax for tracking. Use superpowers:subagent-driven-development only if the user explicitly requested it.

**Goal:** Build a robust, lightweight Util Tools API Server from scratch using Java 21 and Spring Boot 4, featuring 3 essential developer utilities: DateTime/Timezone Converter, Crypto/Encoding Engine, and JSON Data Processor, complete with OpenAPI/Swagger documentation and comprehensive automated tests.

**Architecture:** Layered Spring Boot architecture (Controllers, Services, DTO records, GlobalExceptionHandler) with uniform `ApiResponse<T>` envelopes, strict Jakarta Validation, and comprehensive unit/integration test coverage.

**Tech Stack:** Java 21, Spring Boot 4 (4.1.2.RELEASE), Maven, Spring WebMvc, Jakarta Validation, Jackson, Jayway JsonPath, Springdoc OpenAPI 2.x/3.x, JUnit 5, MockMvc, AssertJ.

## Global Constraints
- Java Version: 21
- Spring Boot Version: 4.1.2.RELEASE
- Project Directory: `/home/aiteam/sample/utiltools_api`
- Consistent REST response envelope: `ApiResponse<T>`
- Full test-driven development (TDD) with tests for every endpoint and service logic

---

### Task 1: Project Scaffolding & Build Configuration

**Files:**
- Create: `pom.xml`
- Create: `src/main/resources/application.properties`
- Create: `src/main/java/com/example/utiltools/UtilToolsApplication.java`
- Create: `src/test/java/com/example/utiltools/UtilToolsApplicationTests.java`

**Interfaces:**
- Consumes: Nothing
- Produces: Runnable Spring Boot 4 application with WebMvc and Validation support

- [ ] **Step 1: Create `pom.xml` with Spring Boot 4 parent and dependencies**
- [ ] **Step 2: Create `application.properties` configuring server port 8080 and application name**
- [ ] **Step 3: Create `UtilToolsApplication.java` main class**
- [ ] **Step 4: Create contextLoads test in `UtilToolsApplicationTests.java`**
- [ ] **Step 5: Run `mvn clean test` to verify build succeeds**
- [ ] **Step 6: Commit changes to git**

---

### Task 2: Common Infrastructure & Unified Response Envelope

**Files:**
- Create: `src/main/java/com/example/utiltools/common/ApiResponse.java`
- Create: `src/main/java/com/example/utiltools/common/ErrorResponse.java`
- Create: `src/main/java/com/example/utiltools/common/GlobalExceptionHandler.java`
- Test: `src/test/java/com/example/utiltools/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: Jakarta Validation, Spring Web exceptions
- Produces: `ApiResponse.success(data, message)`, `ApiResponse.error(message, error, details)` for all controllers

- [ ] **Step 1: Write failing test for `GlobalExceptionHandler` handling validation and business errors**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `ApiResponse.java`, `ErrorResponse.java`, and `GlobalExceptionHandler.java`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit changes to git**

---

### Task 3: DateTime & Timezone Utility

**Files:**
- Create DTOs:
  - `src/main/java/com/example/utiltools/datetime/dto/DateTimeConvertRequest.java`
  - `src/main/java/com/example/utiltools/datetime/dto/DateTimeConvertResponse.java`
  - `src/main/java/com/example/utiltools/datetime/dto/DateTimeDiffRequest.java`
  - `src/main/java/com/example/utiltools/datetime/dto/DateTimeDiffResponse.java`
  - `src/main/java/com/example/utiltools/datetime/dto/CurrentTimeResponse.java`
- Create Service: `src/main/java/com/example/utiltools/datetime/service/DateTimeService.java`
- Create Controller: `src/main/java/com/example/utiltools/datetime/controller/DateTimeController.java`
- Test: `src/test/java/com/example/utiltools/datetime/DateTimeServiceTest.java`
- Test: `src/test/java/com/example/utiltools/datetime/DateTimeControllerTest.java`

**Interfaces:**
- Produces:
  - `POST /api/v1/utils/datetime/convert`
  - `POST /api/v1/utils/datetime/diff`
  - `GET /api/v1/utils/datetime/current`

- [ ] **Step 1: Write failing unit test for `DateTimeService` (convert, diff, current)**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement DTOs and `DateTimeService`**
- [ ] **Step 4: Run test to verify `DateTimeServiceTest` passes**
- [ ] **Step 5: Write failing integration test for `DateTimeController`**
- [ ] **Step 6: Implement `DateTimeController`**
- [ ] **Step 7: Run controller test to verify it passes**
- [ ] **Step 8: Commit changes to git**

---

### Task 4: Crypto & Encoding Utility

**Files:**
- Create DTOs:
  - `src/main/java/com/example/utiltools/crypto/dto/HashRequest.java`
  - `src/main/java/com/example/utiltools/crypto/dto/HashResponse.java`
  - `src/main/java/com/example/utiltools/crypto/dto/Base64Request.java`
  - `src/main/java/com/example/utiltools/crypto/dto/Base64Response.java`
  - `src/main/java/com/example/utiltools/crypto/dto/UuidResponse.java`
- Create Service: `src/main/java/com/example/utiltools/crypto/service/CryptoService.java`
- Create Controller: `src/main/java/com/example/utiltools/crypto/controller/CryptoController.java`
- Test: `src/test/java/com/example/utiltools/crypto/CryptoServiceTest.java`
- Test: `src/test/java/com/example/utiltools/crypto/CryptoControllerTest.java`

**Interfaces:**
- Produces:
  - `POST /api/v1/utils/crypto/hash`
  - `POST /api/v1/utils/crypto/base64`
  - `GET /api/v1/utils/crypto/uuid`

- [ ] **Step 1: Write failing unit test for `CryptoService` (hash, hmac, base64, uuid)**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement DTOs and `CryptoService`**
- [ ] **Step 4: Run test to verify `CryptoServiceTest` passes**
- [ ] **Step 5: Write failing integration test for `CryptoController`**
- [ ] **Step 6: Implement `CryptoController`**
- [ ] **Step 7: Run controller test to verify it passes**
- [ ] **Step 8: Commit changes to git**

---

### Task 5: JSON & Data Processor Utility

**Files:**
- Create DTOs:
  - `src/main/java/com/example/utiltools/json/dto/JsonFormatRequest.java`
  - `src/main/java/com/example/utiltools/json/dto/JsonFormatResponse.java`
  - `src/main/java/com/example/utiltools/json/dto/JsonValidateRequest.java`
  - `src/main/java/com/example/utiltools/json/dto/JsonValidateResponse.java`
  - `src/main/java/com/example/utiltools/json/dto/JsonQueryRequest.java`
  - `src/main/java/com/example/utiltools/json/dto/JsonQueryResponse.java`
- Create Service: `src/main/java/com/example/utiltools/json/service/JsonService.java`
- Create Controller: `src/main/java/com/example/utiltools/json/controller/JsonController.java`
- Test: `src/test/java/com/example/utiltools/json/JsonServiceTest.java`
- Test: `src/test/java/com/example/utiltools/json/JsonControllerTest.java`

**Interfaces:**
- Produces:
  - `POST /api/v1/utils/json/format`
  - `POST /api/v1/utils/json/validate`
  - `POST /api/v1/utils/json/query`

- [ ] **Step 1: Write failing unit test for `JsonService` (format, validate, query)**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement DTOs and `JsonService`**
- [ ] **Step 4: Run test to verify `JsonServiceTest` passes**
- [ ] **Step 5: Write failing integration test for `JsonController`**
- [ ] **Step 6: Implement `JsonController`**
- [ ] **Step 7: Run controller test to verify it passes**
- [ ] **Step 8: Commit changes to git**

---

### Task 6: OpenAPI Documentation & Live End-to-End Verification

**Files:**
- Create: `src/main/java/com/example/utiltools/config/OpenApiConfig.java`
- Create: `README.md`
- Test: Live HTTP curl requests against running server

**Interfaces:**
- Produces: Swagger UI at `/swagger-ui/index.html` and OpenAPI spec at `/v3/api-docs`

- [ ] **Step 1: Add OpenApiConfig bean for API metadata**
- [ ] **Step 2: Build and run all tests with `mvn clean test`**
- [ ] **Step 3: Package application with `mvn clean package -DskipTests=false`**
- [ ] **Step 4: Start application in background, verify all 3 utilities with live curl requests**
- [ ] **Step 5: Document comprehensive README.md with curl examples**
- [ ] **Step 6: Commit and verify final state**
