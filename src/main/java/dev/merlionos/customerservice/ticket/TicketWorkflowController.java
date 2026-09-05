package dev.merlionos.customerservice.ticket;

import dev.merlionos.customerservice.target.ConditionalOnTarget;
import dev.merlionos.customerservice.target.DeploymentTarget;
import dev.merlionos.customerservice.ticket.api.TicketCommand;
import dev.merlionos.customerservice.ticket.api.TicketConflictException;
import dev.merlionos.customerservice.ticket.api.TicketEvent;
import dev.merlionos.customerservice.ticket.api.TicketFilter;
import dev.merlionos.customerservice.ticket.api.TicketNotFoundException;
import dev.merlionos.customerservice.ticket.api.TicketPage;
import dev.merlionos.customerservice.ticket.api.TicketRecord;
import dev.merlionos.customerservice.ticket.api.TicketRuleException;
import dev.merlionos.customerservice.ticket.api.TicketState;
import dev.merlionos.customerservice.ticket.api.TicketWorkflow;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * The workflow seam over HTTP, served only by a {@code ticket} process, for the operations
 * admin running in a {@code chat} process. Records and events cross as the {@code api}
 * records they already are; the three outcomes that are not a record cross as the statuses
 * {@link dev.merlionos.customerservice.clients.HttpTicketWorkflow} turns back into the same
 * exceptions: {@code 404} unknown ticket, {@code 409} lost race or stale version,
 * {@code 422} a move the rules refuse. The message travels in the body so the page can show
 * the same sentence in either topology.
 */
@RestController
@RequestMapping("/internal/v1/ticket-workflow")
@ConditionalOnTarget(value = DeploymentTarget.TICKET, exclusive = true)
class TicketWorkflowController {

    private final TicketWorkflow workflow;

    TicketWorkflowController(TicketWorkflow workflow) {
        this.workflow = workflow;
    }

    @GetMapping("/tickets")
    TicketPage search(@RequestParam(required = false) String state,
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

    @GetMapping("/tickets/{number}")
    ResponseEntity<TicketRecord> find(@PathVariable String number) {
        return workflow.find(number).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tickets/{number}/history")
    List<TicketEvent> history(@PathVariable String number) {
        return workflow.history(number);
    }

    @PostMapping("/tickets/{number}/claim")
    TicketRecord claim(@PathVariable String number, @RequestBody TicketCommand command) {
        return workflow.claim(number, command.actor(), command.expectedVersion());
    }

    @PostMapping("/tickets/{number}/assign")
    TicketRecord assign(@PathVariable String number, @RequestBody TicketCommand command) {
        return workflow.assign(number, command.assignee(), command.actor(), command.expectedVersion());
    }

    @PostMapping("/tickets/{number}/release")
    TicketRecord release(@PathVariable String number, @RequestBody TicketCommand command) {
        return workflow.release(number, command.actor(), command.expectedVersion());
    }

    @PostMapping("/tickets/{number}/resolve")
    TicketRecord resolve(@PathVariable String number, @RequestBody TicketCommand command) {
        return workflow.resolve(number, command.text(), command.actor(), command.expectedVersion());
    }

    @PostMapping("/tickets/{number}/close")
    TicketRecord close(@PathVariable String number, @RequestBody TicketCommand command) {
        return workflow.close(number, command.actor(), command.expectedVersion());
    }

    @PostMapping("/tickets/{number}/reopen")
    TicketRecord reopen(@PathVariable String number, @RequestBody TicketCommand command) {
        return workflow.reopen(number, command.actor(), command.expectedVersion());
    }

    @PostMapping("/tickets/{number}/note")
    TicketRecord note(@PathVariable String number, @RequestBody TicketCommand command) {
        return workflow.addNote(number, command.text(), command.actor(), command.expectedVersion());
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
    ResponseEntity<Map<String, String>> refused(TicketRuleException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
