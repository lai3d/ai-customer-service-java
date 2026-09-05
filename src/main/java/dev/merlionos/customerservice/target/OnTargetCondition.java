package dev.merlionos.customerservice.target;

import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

class OnTargetCondition implements ConfigurationCondition {

    /**
     * Parse phase, deliberately. The default (register phase) would let a skipped role's
     * {@code @ComponentScan} run before the condition is consulted.
     */
    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.PARSE_CONFIGURATION;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnTarget.class.getName());
        if (attributes == null) {
            return true;
        }
        DeploymentTarget role = (DeploymentTarget) attributes.get("value");
        return DeploymentTarget.from(context.getEnvironment()).runs(role);
    }
}
