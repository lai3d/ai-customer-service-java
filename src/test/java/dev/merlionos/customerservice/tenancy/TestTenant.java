package dev.merlionos.customerservice.tenancy;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.util.List;

/**
 * The default tenant's API key as {@code application-test.yml} seeds it, and the one way tests
 * present it: every {@code /api/v1/**} request is a {@code 401} without it.
 */
public final class TestTenant {

    /** Matches {@code app.tenancy.default-api-key} in {@code application-test.yml}. */
    public static final String API_KEY = "cs_testtest-the-default-tenant-key-for-tests";

    public static final String BEARER = "Bearer " + API_KEY;

    private TestTenant() {
    }

    /** Sends the default tenant's key on every request the template makes. Idempotent. */
    public static TestRestTemplate authenticate(TestRestTemplate rest) {
        ClientHttpRequestInterceptor bearer = (request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, BEARER);
            return execution.execute(request, body);
        };
        rest.getRestTemplate().setInterceptors(List.of(bearer));
        return rest;
    }
}
