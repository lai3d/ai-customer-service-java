package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.ticket.api.SupportTicket;
import dev.merlionos.customerservice.ticket.api.TicketActor;
import dev.merlionos.customerservice.ticket.api.TicketConflictException;
import dev.merlionos.customerservice.ticket.api.TicketEvent;
import dev.merlionos.customerservice.ticket.api.TicketFilter;
import dev.merlionos.customerservice.ticket.api.TicketNotFoundException;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketPage;
import dev.merlionos.customerservice.ticket.api.TicketRecord;
import dev.merlionos.customerservice.ticket.api.TicketRuleException;
import dev.merlionos.customerservice.ticket.api.TicketState;
import dev.merlionos.customerservice.ticket.api.TicketWorkflow;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The ticket loop for staff: the queue, a ticket with its history, the actions, and the
 * conversation behind it. Every method runs behind the login; the workflow decides what a
 * person may do to a ticket from {@link TicketActor}, which is built here from the role --
 * an admin may act on tickets other people own, support may not.
 *
 * <p>Three outcomes that are not a ticket become three statuses: {@code 404} unknown,
 * {@code 409} the ticket moved since the page read it (reload and look again), {@code 422}
 * the rules refuse it (reloading will not help). A {@code 422} is also written to
 * {@code admin_audit}; a {@code 409} is not, because losing a race is not a refusal.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/tickets")
class AdminTicketController {

    private final TicketWorkflow workflow;
    private final TicketOperations tickets;
    private final StaffAccounts staff;
    private final ConversationTranscripts transcripts;
    private final AdminAudit audit;

    AdminTicketController(TicketWorkflow workflow, TicketOperations tickets, StaffAccounts staff,
                          ConversationTranscripts transcripts, AdminAudit audit) {
        this.workflow = workflow;
        this.tickets = tickets;
        this.staff = staff;
        this.transcripts = transcripts;
        this.audit = audit;
    }

    @GetMapping
    TicketPage list(@RequestParam(required = false) String state,
                    @RequestParam(required = false) String owner,
                    @RequestParam(required = false) String from,
                    @RequestParam(required = false) String to,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "0") int size) {
        // Parsed by hand: Spring's default conversion wants the enum's constant name, and the
        // page and the client both speak the lower-case wire form.
        return workflow.search(new TicketFilter(blank(state) ? null : TicketState.fromValue(state), owner,
                blank(from) ? null : Instant.parse(from), blank(to) ? null : Instant.parse(to), page, size));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record TicketDetail(TicketRecord ticket, List<TicketEvent> history) {
    }

    @GetMapping("/{number}")
    TicketDetail detail(@PathVariable String number) {
        TicketRecord ticket = workflow.find(number).orElseThrow(() -> new TicketNotFoundException(number));
        return new TicketDetail(ticket, workflow.history(number));
    }

    /**
     * @param notPersisted what the page cannot show and why, so the transcript is not read
     *                     as the whole record
     */
    record Conversation(String conversationId, List<ConversationTranscripts.Message> messages,
                        List<SupportTicket> tickets, String notPersisted) {
    }

    /** Opening a conversation is recorded: this is the one page that shows customer text on purpose. */
    @GetMapping("/{number}/conversation")
    Conversation conversation(@PathVariable String number, Authentication authentication) {
        TicketRecord ticket = workflow.find(number).orElseThrow(() -> new TicketNotFoundException(number));
        audit.record(authentication.getName(), AdminAudit.Action.VIEWED_CONVERSATION, ticket.conversationId(),
                "from ticket " + number);
        return new Conversation(ticket.conversationId(), transcripts.messages(ticket.conversationId()),
                tickets.ticketsFor(ticket.conversationId()), ConversationTranscripts.NOT_PERSISTED);
    }

    /** @param text the note for {@code note}, the conclusion for {@code resolve} */
    record Command(int expectedVersion, String assignee, String text) {
    }

    @PostMapping("/{number}/claim")
    TicketRecord claim(@PathVariable String number, @RequestBody Command command, Authentication auth) {
        return workflow.claim(number, actor(auth), command.expectedVersion());
    }

    @PostMapping("/{number}/assign")
    TicketRecord assign(@PathVariable String number, @RequestBody Command command, Authentication auth) {
        String assignee = command.assignee() == null ? "" : command.assignee();
        if (staff.find(assignee).filter(StaffAccount::enabled).isEmpty()) {
            throw new TicketRuleException(number, "cannot be assigned to '" + assignee.strip()
                    + "': no enabled staff account with that name");
        }
        return workflow.assign(number, assignee, actor(auth), command.expectedVersion());
    }

    @PostMapping("/{number}/release")
    TicketRecord release(@PathVariable String number, @RequestBody Command command, Authentication auth) {
        return workflow.release(number, actor(auth), command.expectedVersion());
    }

    @PostMapping("/{number}/resolve")
    TicketRecord resolve(@PathVariable String number, @RequestBody Command command, Authentication auth) {
        return workflow.resolve(number, command.text(), actor(auth), command.expectedVersion());
    }

    @PostMapping("/{number}/close")
    TicketRecord close(@PathVariable String number, @RequestBody Command command, Authentication auth) {
        return workflow.close(number, actor(auth), command.expectedVersion());
    }

    @PostMapping("/{number}/reopen")
    TicketRecord reopen(@PathVariable String number, @RequestBody Command command, Authentication auth) {
        return workflow.reopen(number, actor(auth), command.expectedVersion());
    }

    @PostMapping("/{number}/note")
    TicketRecord note(@PathVariable String number, @RequestBody Command command, Authentication auth) {
        return workflow.addNote(number, command.text(), actor(auth), command.expectedVersion());
    }

    static TicketActor actor(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(StaffRole.ADMIN.authority()));
        return new TicketActor(authentication.getName(), admin);
    }

    @ExceptionHandler(TicketNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(TicketNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TicketConflictException.class)
    ResponseEntity<Map<String, String>> conflict(TicketConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TicketRuleException.class)
    ResponseEntity<Map<String, String>> refused(TicketRuleException e, Authentication authentication) {
        audit.record(authentication.getName(), AdminAudit.Action.REFUSED, target(e.getMessage()), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** The ticket number the rule exception names: "Ticket TKT-4701 is ...". */
    private static String target(String message) {
        String[] words = message.split(" ", 3);
        return words.length > 1 ? words[1] : "?";
    }
}
