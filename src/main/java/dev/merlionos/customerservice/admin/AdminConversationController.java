package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.chat.TurnRecords;
import dev.merlionos.customerservice.ticket.api.SupportTicket;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Conversations as the operational record shows them: a list of conversations from
 * {@code conversation_turn}, and one conversation as its turns with the evidence each was
 * grounded on, the tools it called, how it ended and what it cost. Read-only. Opening a
 * conversation is recorded in {@code admin_audit}, as it is from a ticket: this is customer
 * text, shown on purpose.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/conversations")
class AdminConversationController {

    private final TurnRecords records;
    private final TicketOperations tickets;
    private final AnswerFeedback feedback;
    private final AdminAudit audit;

    AdminConversationController(TurnRecords records, TicketOperations tickets, AnswerFeedback feedback, AdminAudit audit) {
        this.records = records;
        this.tickets = tickets;
        this.feedback = feedback;
        this.audit = audit;
    }

    @GetMapping
    TurnRecords.Page list(@RequestParam(required = false) String conversationId,
                          @RequestParam(required = false) String outcome,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "0") int size) {
        return records.conversations(new TurnRecords.Filter(conversationId, outcome,
                blank(from) ? null : Instant.parse(from), blank(to) ? null : Instant.parse(to), page, size));
    }

    /** @param notPersisted what the record does not hold, so the turns are not read as everything */
    record ConversationDetail(String conversationId, List<TurnRecords.Turn> turns, List<SupportTicket> tickets,
                              List<AnswerFeedback.Report> feedback, String notPersisted) {
    }

    static final String NOT_PERSISTED = "Turns are recorded from the moment this record existed; earlier "
            + "conversations have only their chat memory. The model's own reasoning is never recorded.";

    @GetMapping("/{conversationId}")
    ConversationDetail detail(@PathVariable String conversationId, Authentication authentication) {
        List<TurnRecords.Turn> turns = records.turns(conversationId);
        if (turns.isEmpty()) {
            throw new NoSuchConversation(conversationId);
        }
        audit.record(authentication.getName(), AdminAudit.Action.VIEWED_CONVERSATION, conversationId,
                "from conversations");
        return new ConversationDetail(conversationId, turns, tickets.ticketsFor(conversationId),
                feedback.forConversation(conversationId), NOT_PERSISTED);
    }

    static class NoSuchConversation extends RuntimeException {
        NoSuchConversation(String id) {
            super("No recorded turns for conversation " + id);
        }
    }

    @ExceptionHandler(NoSuchConversation.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchConversation e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
