package dev.merlionos.customerservice.ticket;

import dev.merlionos.customerservice.target.ConditionalOnTarget;
import dev.merlionos.customerservice.target.DeploymentTarget;
import dev.merlionos.customerservice.ticket.api.OperationConflictException;
import dev.merlionos.customerservice.ticket.api.SupportTicket;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketRequest;
import dev.merlionos.customerservice.ticket.api.TicketResult;
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

import java.util.List;

/**
 * The ticket seam over HTTP, served only by a {@code ticket} process. The body of a request is
 * the {@link TicketRequest} record and the body of a response is the {@link TicketResult}
 * record: the wire format is the contract the local call already used, and a business outcome
 * -- duplicate, capped -- is a {@code 200} with that outcome in it, never an error status.
 * Only a programming error on the caller's side (an operation id reused with different input)
 * and a missing operation are statuses.
 */
@RestController
@RequestMapping("/internal/v1")
@ConditionalOnTarget(value = DeploymentTarget.TICKET, exclusive = true)
class TicketController {

    private final TicketOperations tickets;

    TicketController(TicketOperations tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/tickets")
    TicketResult create(@RequestBody TicketRequest request) {
        return tickets.create(request);
    }

    @GetMapping("/ticket-operations/{operationId}")
    ResponseEntity<TicketResult> recorded(@PathVariable String operationId) {
        return tickets.recorded(operationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tickets")
    List<SupportTicket> ticketsFor(@RequestParam String conversationId) {
        return tickets.ticketsFor(conversationId);
    }

    @ExceptionHandler(OperationConflictException.class)
    ResponseEntity<String> conflict(OperationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.getMessage());
    }
}
