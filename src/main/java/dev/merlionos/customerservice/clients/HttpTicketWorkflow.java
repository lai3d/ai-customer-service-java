package dev.merlionos.customerservice.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.merlionos.customerservice.ticket.api.TicketActor;
import dev.merlionos.customerservice.ticket.api.TicketCommand;
import dev.merlionos.customerservice.ticket.api.TicketConflictException;
import dev.merlionos.customerservice.ticket.api.TicketEvent;
import dev.merlionos.customerservice.ticket.api.TicketFilter;
import dev.merlionos.customerservice.ticket.api.TicketNotFoundException;
import dev.merlionos.customerservice.ticket.api.TicketPage;
import dev.merlionos.customerservice.ticket.api.TicketRecord;
import dev.merlionos.customerservice.ticket.api.TicketRuleException;
import dev.merlionos.customerservice.ticket.api.TicketWorkflow;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The workflow seam as a client, for a {@code chat} process. Unlike
 * {@link HttpTicketOperations} there is no retry and no recovery here: the caller is a
 * person at a page, not a model mid-turn, and every mutation carries the version it read, so
 * a request whose answer was lost is simply resubmitted and either applies or comes back as
 * a conflict. A transport failure surfaces as the exception it is; the page says the service
 * could not be reached.
 *
 * <p>The three non-record statuses come back as the exceptions the local implementation
 * throws, with the server's sentence, so the admin behaves the same in both topologies.
 */
public class HttpTicketWorkflow implements TicketWorkflow {

    private static final String BASE = "/internal/v1/ticket-workflow/tickets";
    private static final ParameterizedTypeReference<List<TicketEvent>> EVENTS = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();

    public HttpTicketWorkflow(RestClient client) {
        this.client = client;
    }

    @Override
    public Optional<TicketRecord> find(String ticketNumber) {
        try {
            return Optional.ofNullable(client.get().uri(BASE + "/{n}", ticketNumber).retrieve().body(TicketRecord.class));
        }
        catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public TicketPage search(TicketFilter filter) {
        TicketPage page = client.get()
                .uri(builder -> {
                    builder.path(BASE).queryParam("page", filter.page()).queryParam("size", filter.size());
                    if (filter.state() != null) {
                        builder.queryParam("state", filter.state().value());
                    }
                    if (filter.owner() != null) {
                        builder.queryParam("owner", filter.owner());
                    }
                    if (filter.from() != null) {
                        builder.queryParam("from", filter.from().toString());
                    }
                    if (filter.to() != null) {
                        builder.queryParam("to", filter.to().toString());
                    }
                    return builder.build();
                })
                .retrieve().body(TicketPage.class);
        return page == null ? new TicketPage(List.of(), 0, filter.page(), filter.size()) : page;
    }

    @Override
    public List<TicketEvent> history(String ticketNumber) {
        List<TicketEvent> events = client.get().uri(BASE + "/{n}/history", ticketNumber).retrieve().body(EVENTS);
        return events == null ? List.of() : events;
    }

    @Override
    public TicketRecord claim(String ticketNumber, TicketActor actor, int expectedVersion) {
        return post(ticketNumber, "claim", TicketCommand.of(actor, expectedVersion));
    }

    @Override
    public TicketRecord assign(String ticketNumber, String assignee, TicketActor actor, int expectedVersion) {
        return post(ticketNumber, "assign", new TicketCommand(actor, expectedVersion, assignee, null));
    }

    @Override
    public TicketRecord release(String ticketNumber, TicketActor actor, int expectedVersion) {
        return post(ticketNumber, "release", TicketCommand.of(actor, expectedVersion));
    }

    @Override
    public TicketRecord resolve(String ticketNumber, String conclusion, TicketActor actor, int expectedVersion) {
        return post(ticketNumber, "resolve", new TicketCommand(actor, expectedVersion, null, conclusion));
    }

    @Override
    public TicketRecord close(String ticketNumber, TicketActor actor, int expectedVersion) {
        return post(ticketNumber, "close", TicketCommand.of(actor, expectedVersion));
    }

    @Override
    public TicketRecord reopen(String ticketNumber, TicketActor actor, int expectedVersion) {
        return post(ticketNumber, "reopen", TicketCommand.of(actor, expectedVersion));
    }

    @Override
    public TicketRecord addNote(String ticketNumber, String note, TicketActor actor, int expectedVersion) {
        return post(ticketNumber, "note", new TicketCommand(actor, expectedVersion, null, note));
    }

    private TicketRecord post(String ticketNumber, String action, TicketCommand command) {
        try {
            return client.post().uri(BASE + "/{n}/" + action, ticketNumber).body(command).retrieve().body(TicketRecord.class);
        }
        catch (HttpClientErrorException e) {
            String message = message(e, ticketNumber);
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new TicketNotFoundException(ticketNumber);
            }
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new TicketConflictException(ticketNumber, message);
            }
            if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                throw new TicketRuleException(ticketNumber, message);
            }
            throw e;
        }
    }

    /** The server's sentence minus the "Ticket X " prefix the exception will put back. */
    private String message(HttpClientErrorException e, String ticketNumber) {
        try {
            Map<?, ?> body = json.readValue(e.getResponseBodyAsString(), Map.class);
            String error = String.valueOf(body.get("error"));
            String prefix = "Ticket " + ticketNumber + " ";
            return error.startsWith(prefix) ? error.substring(prefix.length()) : error;
        }
        catch (Exception ignored) {
            return "was refused by the ticket service (" + e.getStatusCode() + ")";
        }
    }
}
