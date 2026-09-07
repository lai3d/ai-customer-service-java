package dev.merlionos.customerservice.tenancy;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where the resolved tenant lives for the rest of a public request: a request attribute set
 * by {@link ApiKeyFilter} and read by the controller, which passes the tenant id down as a
 * plain argument. Not a thread-local: the SSE stream runs on Reactor threads and the tools on
 * Spring AI's scheduler, so the tenant travels the way the turn id already does -- in the
 * tool context and the advisor parameters -- and nothing reads it from a thread.
 */
public final class TenantContext {

    static final String ATTRIBUTE = TenantContext.class.getName() + ".tenant";

    private TenantContext() {
    }

    public static Tenant require(HttpServletRequest request) {
        Object tenant = request.getAttribute(ATTRIBUTE);
        if (!(tenant instanceof Tenant resolved)) {
            throw new IllegalStateException("No tenant on this request; ApiKeyFilter did not run");
        }
        return resolved;
    }

    static void set(HttpServletRequest request, Tenant tenant) {
        request.setAttribute(ATTRIBUTE, tenant);
    }
}
