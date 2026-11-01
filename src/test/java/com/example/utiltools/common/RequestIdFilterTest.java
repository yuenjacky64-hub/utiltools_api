package com.example.utiltools.common;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("a valid incoming X-Request-Id is reused on attribute, context and response")
    void echoesIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/utils/crypto/hash");
        request.addHeader(RequestContext.HEADER, "client-id_12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestContext.HEADER)).isEqualTo("client-id_12345");
        assertThat(request.getAttribute(RequestContext.ATTRIBUTE)).isEqualTo("client-id_12345");
        assertThat(chain.getRequest()).isNotNull();
        assertThat(RequestContext.get()).isNull();
    }

    @Test
    @DisplayName("a malformed header is replaced by a generated UUID")
    void shouldReplaceMalformedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/utils/crypto/uuids");
        request.addHeader(RequestContext.HEADER, "short");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(RequestContext.HEADER);
        assertThat(echoed).isNotNull().isNotEqualTo("short");
        assertThat(echoed).matches("^[A-Za-z0-9_-]{8,64}$");
        assertThat(echoed).isEqualTo((String) request.getAttribute(RequestContext.ATTRIBUTE));
    }

    @Test
    @DisplayName("two requests without the header receive different ids and the ThreadLocal is cleared")
    void generatesDistinctIdsPerRequest() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/utils/datetime/current");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());

        assertThat(RequestContext.get()).isNull();

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/utils/datetime/current");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertThat(firstResponse.getHeader(RequestContext.HEADER))
                .isNotNull()
                .isNotEqualTo(secondResponse.getHeader(RequestContext.HEADER));
        assertThat(RequestContext.get()).isNull();
    }

    @Test
    @DisplayName("the ThreadLocal is cleared even when the chain throws")
    void shouldClearThreadLocalOnFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/utils/json/query");
        request.addHeader(RequestContext.HEADER, "client-id_99999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(RequestContext.get()).isNull();
    }

    @Test
    @DisplayName("non /api/v1 paths are not filtered")
    void shouldSkipNonApiV1Paths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestContext.HEADER)).isNull();
        assertThat(request.getAttribute(RequestContext.ATTRIBUTE)).isNull();
        assertThat(RequestContext.get()).isNull();
    }

    @Test
    @DisplayName("ApiResponse carries the current request id")
    void shouldPopulateApiResponseRequestId() {
        RequestContext.set("req-abcdefgh");

        ApiResponse<String> success = ApiResponse.success("ok");
        ApiResponse<String> error = ApiResponse.error("bad", "BAD_REQUEST", null);

        assertThat(success.requestId()).isEqualTo("req-abcdefgh");
        assertThat(error.requestId()).isEqualTo("req-abcdefgh");
        assertThat(success.timestamp()).isNotNull();
        assertThat(error.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("normalize accepts only the documented charset and length")
    void shouldValidateCandidateIds() {
        assertThat(RequestContext.normalize("abcDEF123_-")).contains("abcDEF123_-");
        assertThat(RequestContext.normalize("abc def1234")).isEmpty();
        assertThat(RequestContext.normalize("short")).isEmpty();
        assertThat(RequestContext.normalize("a".repeat(65))).isEmpty();
        assertThat(RequestContext.normalize(null)).isEmpty();
    }
}
