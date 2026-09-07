package dev.merlionos.customerservice.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultApiKey a key for the default tenant, issued at startup into an empty
 *                      {@code tenant_api_key} table and never again. Without it a fresh
 *                      install has a tenant and no way to talk to it, which startup says
 * @param metricsLabelLimit above this many tenants, spend meters drop the {@code tenant}
 *                          label rather than let its cardinality grow
 */
@ConfigurationProperties("app.tenancy")
public record TenancyProperties(String defaultApiKey, Integer metricsLabelLimit) {

    public int metricsLabelLimitOrDefault() {
        return metricsLabelLimit == null ? 200 : metricsLabelLimit;
    }
}
