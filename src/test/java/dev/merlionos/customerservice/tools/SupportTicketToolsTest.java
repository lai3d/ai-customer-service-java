package dev.merlionos.customerservice.tools;

import dev.merlionos.customerservice.chat.TurnEventBus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportTicketToolsTest {

    private static final String CONVERSATION = "conversation-7";

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SupportTicketTools tools = new SupportTicketTools(meterRegistry, new TurnEventBus());

    private static ToolContext context(String conversationId) {
        return new ToolContext(Map.of(SupportTicketTools.CONVERSATION_ID_KEY, conversationId));
    }

    @Test
    @DisplayName("a ticket is created and attributed to the conversation that raised it")
    void createsTicket() {
        SupportTicket ticket = tools.createSupportTicket(
                "Customer wants a refund decision on a damaged lamp", "returns", "ORD-10045",
                context(CONVERSATION));

        assertThat(ticket.ticketNumber()).startsWith("TKT-");
        assertThat(ticket.conversationId()).isEqualTo(CONVERSATION);
        assertThat(ticket.category()).isEqualTo("returns");
        assertThat(ticket.orderNumber()).isEqualTo("ORD-10045");
        assertThat(ticket.alreadyExisted()).isFalse();
    }

    @Test
    @DisplayName("asking twice in one conversation returns the same ticket, not a second one")
    void deduplicatesWithinAConversation() {
        SupportTicket first = tools.createSupportTicket(
                "Customer wants a refund decision", "returns", null, context(CONVERSATION));
        SupportTicket second = tools.createSupportTicket(
                "  customer WANTS a   refund decision ", "returns", null, context(CONVERSATION));

        assertThat(second.ticketNumber()).isEqualTo(first.ticketNumber());
        assertThat(second.alreadyExisted()).isTrue();
        assertThat(tools.ticketsFor(CONVERSATION)).hasSize(1);
    }

    @Test
    @DisplayName("different conversations with identical wording get their own tickets")
    void doesNotDeduplicateAcrossConversations() {
        tools.createSupportTicket("Where is my refund", "payment", null, context("conversation-a"));
        tools.createSupportTicket("Where is my refund", "payment", null, context("conversation-b"));

        assertThat(tools.ticketsFor("conversation-a")).hasSize(1);
        assertThat(tools.ticketsFor("conversation-b")).hasSize(1);
    }

    @Test
    @DisplayName("an unrecognised category falls back to other rather than being stored as-is")
    void normalisesCategory() {
        SupportTicket ticket = tools.createSupportTicket(
                "Something else entirely", "URGENT!!", null, context(CONVERSATION));

        assertThat(ticket.category()).isEqualTo("other");
    }

    @Test
    @DisplayName("a ticket cannot be created without knowing which conversation it belongs to")
    void requiresConversationId() {
        assertThatThrownBy(() -> tools.createSupportTicket(
                "Anything", "other", null, new ToolContext(Map.of("unrelated", "value"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SupportTicketTools.CONVERSATION_ID_KEY);
    }

    @Test
    @DisplayName("creations and suppressed duplicates are counted separately")
    void invocationsAreMetered() {
        tools.createSupportTicket("Same problem", "other", null, context(CONVERSATION));
        tools.createSupportTicket("Same problem", "other", null, context(CONVERSATION));

        assertThat(counter("created")).isEqualTo(1.0);
        assertThat(counter("duplicate_suppressed")).isEqualTo(1.0);
    }

    private double counter(String outcome) {
        return meterRegistry.get("chat.tool.invocations")
                .tag("tool", "create_support_ticket").tag("outcome", outcome).counter().count();
    }
}
