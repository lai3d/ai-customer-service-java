package dev.merlionos.customerservice.target;

import dev.merlionos.customerservice.CustomerServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentTargetTest {

    @ParameterizedTest(name = "app.target={0} parses to {1}")
    @CsvSource({"all, ALL", "ALL, ALL", " chat , CHAT", "knowledge, KNOWLEDGE", "Ticket, TICKET", "'', ALL"})
    void parsesCaseAndWhitespaceInsensitively(String value, DeploymentTarget expected) {
        assertThat(DeploymentTarget.parse(value)).isEqualTo(expected);
    }

    @Test
    void absentMeansAll() {
        assertThat(DeploymentTarget.parse(null)).isEqualTo(DeploymentTarget.ALL);
        assertThat(DeploymentTarget.from(new MockEnvironment())).isEqualTo(DeploymentTarget.ALL);
    }

    @Test
    @DisplayName("a misspelt target names the valid values instead of half-starting")
    void rejectsUnknownTarget() {
        assertThatThrownBy(() -> DeploymentTarget.parse("chatt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.target")
                .hasMessageContaining("[all, chat, knowledge, ticket]");
    }

    @Test
    void allRunsEveryRoleAndARoleRunsOnlyItself() {
        for (DeploymentTarget role : DeploymentTarget.values()) {
            assertThat(DeploymentTarget.ALL.runs(role)).isTrue();
        }
        assertThat(DeploymentTarget.TICKET.runs(DeploymentTarget.TICKET)).isTrue();
        assertThat(DeploymentTarget.TICKET.runs(DeploymentTarget.CHAT)).isFalse();
        assertThat(DeploymentTarget.CHAT.runs(DeploymentTarget.KNOWLEDGE)).isFalse();
    }

    // --- the condition, on real configuration classes ---------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnTarget(DeploymentTarget.CHAT)
    static class ChatOnly {
        @Bean String chatBean() { return "chat"; }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnTarget(DeploymentTarget.TICKET)
    static class TicketOnly {
        @Bean String ticketBean() { return "ticket"; }
    }

    private static AnnotationConfigApplicationContext contextFor(String target) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(new MockEnvironment().withProperty(DeploymentTarget.PROPERTY, target));
        context.register(ChatOnly.class, TicketOnly.class);
        context.refresh();
        return context;
    }

    @Test
    @DisplayName("all admits every role's configuration")
    void allAdmitsEveryRole() {
        try (var context = contextFor("all")) {
            assertThat(context.containsBean("chatBean")).isTrue();
            assertThat(context.containsBean("ticketBean")).isTrue();
        }
    }

    @Test
    @DisplayName("a role admits only its own configuration")
    void roleAdmitsOnlyItself() {
        try (var context = contextFor("ticket")) {
            assertThat(context.containsBean("chatBean")).isFalse();
            assertThat(context.containsBean("ticketBean")).isTrue();
        }
    }

    // --- the property overrides and the startup guard --------------------------------------

    @Test
    @DisplayName("all runs application.yml as written; every other role switches something off")
    void overridesPerTarget() {
        assertThat(TargetEnvironmentPostProcessor.overridesFor(DeploymentTarget.ALL)).isEmpty();
        assertThat(TargetEnvironmentPostProcessor.overridesFor(DeploymentTarget.CHAT))
                .containsEntry("spring.ai.model.embedding", "none")
                .containsEntry("spring.ai.vectorstore.type", "none")
                .doesNotContainKey("spring.ai.model.chat");
        assertThat(TargetEnvironmentPostProcessor.overridesFor(DeploymentTarget.KNOWLEDGE))
                .containsEntry("spring.ai.model.chat", "none")
                .doesNotContainKey("spring.ai.model.embedding")
                .doesNotContainKey("spring.ai.vectorstore.type");
        assertThat(TargetEnvironmentPostProcessor.overridesFor(DeploymentTarget.TICKET))
                .containsEntry("spring.ai.model.chat", "none")
                .containsEntry("spring.ai.model.embedding", "none")
                .containsEntry("spring.ai.vectorstore.type", "none");
    }

    @Test
    @DisplayName("a single role without the properties it needs fails before any auto-configuration runs, naming them")
    void singleRoleRequiresItsConfiguration() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(CustomerServiceApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                // A command-line argument, not .properties(): those are *default* properties
                // and application.yml's own `app.target` would win over them.
                .run("--app.target=chat"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.target=chat")
                .hasMessageContaining("app.services.knowledge.url")
                .hasMessageContaining("app.services.ticket.url")
                .hasMessageContaining("app.internal.token");
        assertThatThrownBy(() -> new SpringApplicationBuilder(CustomerServiceApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--app.target=ticket"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.internal.token");
    }

    @Test
    @DisplayName("an unknown target fails the same way")
    void unknownTargetFailsAtStartup() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(CustomerServiceApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--app.target=bogus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown app.target 'bogus'");
    }
}
