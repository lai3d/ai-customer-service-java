package dev.merlionos.customerservice.target;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a configuration class, and everything it scans or declares, on the process running
 * the given role. A process started as {@code all} matches every role, unless
 * {@link #exclusive()} is set, which means "this role, and not {@code all}": the internal
 * endpoints and the HTTP adapters exist only when the roles are in separate processes.
 *
 * <p>Evaluated in both parse and registration phases, so it works on a role's
 * {@code @Configuration} (where it stops the {@code @ComponentScan}) and on a scanned
 * component such as a controller.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnTargetCondition.class)
public @interface ConditionalOnTarget {

    DeploymentTarget value();

    /** Match only when the process is exactly this role, not {@code all}. */
    boolean exclusive() default false;
}
