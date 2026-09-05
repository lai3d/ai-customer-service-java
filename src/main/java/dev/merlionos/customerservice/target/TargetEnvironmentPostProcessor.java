package dev.merlionos.customerservice.target;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Validates {@code app.target} before any auto-configuration has run, so a misspelt target
 * is a one-line startup failure rather than a context that half-assembles.
 *
 * <p>This is also where the per-target property overrides will go: which Spring AI model
 * types a role initialises, whether it has a datasource, whether it imports the corpus. Bean
 * conditions cannot do that job, because those are switches on auto-configurations that run
 * before any bean of ours is considered. Until role composition lands, only {@code all} is
 * runnable and the other three fail here with a message that says so; their configuration
 * classes exist and are gated, but the property overrides they need are not written yet.
 */
public class TargetEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        DeploymentTarget target = DeploymentTarget.from(environment);
        if (target != DeploymentTarget.ALL) {
            throw new IllegalStateException(DeploymentTarget.PROPERTY + "=" + target.name().toLowerCase()
                    + " is defined but not yet runnable: the role's beans are gated, but the "
                    + "property overrides it needs are not written. Run with app.target=all.");
        }
    }
}
