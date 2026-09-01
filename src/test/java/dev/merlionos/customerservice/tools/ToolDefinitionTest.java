package dev.merlionos.customerservice.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts on the tool contract the model actually receives.
 *
 * <p>Tool names, descriptions, and schemas are prompt: they are the whole basis on which the
 * model decides whether to call a tool and what to pass it. A rename, a dropped description,
 * or a parameter that silently becomes required changes model behaviour without changing any
 * behaviour these tests could otherwise observe.
 */
class ToolDefinitionTest {

    private final Map<String, ToolCallback> callbacks = Arrays.stream(ToolCallbacks.from(
                    new OrderTools(new MockOrderRepository(), new SimpleMeterRegistry()),
                    new SupportTicketTools(new SimpleMeterRegistry())))
            .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), Function.identity()));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("both tools are exposed under their intended names")
    void exposesBothTools() {
        assertThat(callbacks).containsOnlyKeys("lookup_order_status", "create_support_ticket");
    }

    @Test
    @DisplayName("every tool carries a description telling the model when to use it")
    void everyToolIsDescribed() {
        assertThat(callbacks.values())
                .allSatisfy(callback -> assertThat(callback.getToolDefinition().description())
                        .as("tool %s", callback.getToolDefinition().name())
                        .isNotBlank()
                        .hasSizeGreaterThan(80));
    }

    @Test
    @DisplayName("parameter names survive compilation, so the schema is not arg0/arg1")
    void parameterNamesArePreserved() throws Exception {
        // Without javac's -parameters flag these would be "arg0" and "arg1", and the model
        // would be guessing what to put in each field.
        assertThat(properties("create_support_ticket").fieldNames())
                .toIterable().containsExactly("summary", "category", "orderNumber");
    }

    @Test
    @DisplayName("every parameter is described, not just typed")
    void everyParameterIsDescribed() throws Exception {
        for (String tool : callbacks.keySet()) {
            properties(tool).fields().forEachRemaining(field ->
                    assertThat(field.getValue().path("description").asText())
                            .as("%s.%s", tool, field.getKey())
                            .isNotBlank());
        }
    }

    @Test
    @DisplayName("an optional parameter is optional in the schema too")
    void optionalParameterIsNotRequired() throws Exception {
        JsonNode required = schema("create_support_ticket").path("required");

        assertThat(required.toString()).contains("summary").contains("category");
        assertThat(required.toString())
                .as("orderNumber is annotated required=false; forcing it would make the model "
                        + "invent order numbers for customers who never gave one")
                .doesNotContain("orderNumber");
    }

    @Test
    @DisplayName("ToolContext is a server-side concern and stays out of the model's schema")
    void toolContextIsNotExposed() throws Exception {
        assertThat(schema("create_support_ticket").toString())
                .doesNotContain("toolContext")
                .doesNotContain("ToolContext")
                .doesNotContain(SupportTicketTools.CONVERSATION_ID_KEY);
    }

    private JsonNode schema(String tool) throws Exception {
        return objectMapper.readTree(callbacks.get(tool).getToolDefinition().inputSchema());
    }

    private JsonNode properties(String tool) throws Exception {
        return schema(tool).path("properties");
    }
}
