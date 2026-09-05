package dev.merlionos.customerservice.target;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * A plain {@link Condition}, deliberately not a {@code ConfigurationCondition}: a phased
 * condition is only consulted in its phase, and a scanned controller is registered in the
 * registration phase, so a parse-phase condition on it would never run and the controller
 * would be present in every process.
 */
class OnTargetCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnTarget.class.getName());
        if (attributes == null) {
            return true;
        }
        DeploymentTarget role = (DeploymentTarget) attributes.get("value");
        boolean exclusive = Boolean.TRUE.equals(attributes.get("exclusive"));
        DeploymentTarget target = DeploymentTarget.from(context.getEnvironment());
        return exclusive ? target == role : target.runs(role);
    }
}
