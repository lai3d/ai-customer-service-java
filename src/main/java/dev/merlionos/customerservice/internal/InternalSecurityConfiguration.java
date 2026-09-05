package dev.merlionos.customerservice.internal;

import dev.merlionos.customerservice.target.DeploymentTarget;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Installs the token filter in every process that is not {@code all}. */
@Configuration(proxyBeanMethods = false)
@Conditional(InternalSecurityConfiguration.OnSplit.class)
public class InternalSecurityConfiguration {

    static class OnSplit implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                               org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            return DeploymentTarget.from(context.getEnvironment()) != DeploymentTarget.ALL;
        }
    }

    @Bean
    FilterRegistrationBean<InternalAuthFilter> internalAuthFilter(InternalProperties properties, Environment environment) {
        FilterRegistrationBean<InternalAuthFilter> registration =
                new FilterRegistrationBean<>(new InternalAuthFilter(properties.token()));
        registration.addUrlPatterns(InternalAuthFilter.PATH_PREFIX + "*");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}
