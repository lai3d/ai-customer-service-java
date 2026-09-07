package dev.merlionos.customerservice.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/** The filter on the public API, and the one-time seed of the default tenant's key. */
@Configuration(proxyBeanMethods = false)
public class TenancyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TenancyConfiguration.class);

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(TenantApiKeys keys) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(new ApiKeyFilter(keys));
        registration.addUrlPatterns(ApiKeyFilter.PATH_PREFIX + "*");
        registration.setOrder(Integer.MIN_VALUE + 10);
        return registration;
    }

    @Bean
    TenantSeeder tenantSeeder(TenantApiKeys keys, TenancyProperties properties) {
        return new TenantSeeder(keys, properties);
    }

    /**
     * Seeds {@code app.tenancy.default-api-key} for the default tenant into an empty key
     * table, the way the first admin is seeded; a table with any key is left alone, so a
     * rotated or revoked key does not come back on the next start.
     */
    public static class TenantSeeder {

        private final TenantApiKeys keys;
        private final TenancyProperties properties;

        TenantSeeder(TenantApiKeys keys, TenancyProperties properties) {
            this.keys = keys;
            this.properties = properties;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void seed() {
            if (keys.any()) {
                return;
            }
            String key = properties.defaultApiKey();
            if (key == null || key.isBlank()) {
                log.warn("No tenant API key exists and app.tenancy.default-api-key is unset: "
                        + "the public API answers 401 to everyone until a key is issued");
                return;
            }
            if (!key.startsWith(TenantApiKeys.PREFIX) || key.length() < TenantApiKeys.PREFIX.length() + 9) {
                throw new IllegalStateException("app.tenancy.default-api-key must look like cs_<8 id chars><secret>");
            }
            keys.store(Tenant.DEFAULT, key, "seeded default tenant key");
            log.info("Seeded the default tenant's API key (id {})", TenantApiKeys.keyId(key));
        }
    }
}
