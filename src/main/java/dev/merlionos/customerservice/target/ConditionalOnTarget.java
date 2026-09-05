package dev.merlionos.customerservice.target;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a configuration class, and everything it scans or declares, on the process running
 * the given role. A process started as {@code all} matches every role.
 *
 * <p>Applied to configuration classes, not to individual components: a role is a set of
 * packages, and the condition on the class that scans them is what keeps a {@code ticket}
 * process from discovering the chat controller. Evaluated at parse time, so a skipped class
 * contributes no bean definitions at all rather than beans that are later removed.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnTargetCondition.class)
public @interface ConditionalOnTarget {

    DeploymentTarget value();
}
