package dev.merlionos.customerservice.clients;

import dev.merlionos.customerservice.ticket.api.OperationConflictException;
import dev.merlionos.customerservice.ticket.api.SupportTicket;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketRequest;
import dev.merlionos.customerservice.ticket.api.TicketResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * The ticket seam as a client, with the write treated as the write it is.
 *
 * <p>A timeout on a POST is ambiguous: the ticket may or may not have been committed. The
 * sequence here is what the ADR settled on. One attempt; on failure one retry with the same
 * operation id, which the server answers from its record if the first attempt did commit;
 * then one recovery read of that record; and only then {@link TicketResult#unavailable()}, a
 * value telling the model to apologise and promise a human. The model is never handed a
 * transport error, and the customer is never told a ticket failed that exists.
 */
public class HttpTicketOperations implements TicketOperations {

    private static final Logger log = LoggerFactory.getLogger(HttpTicketOperations.class);
    private static final ParameterizedTypeReference<List<SupportTicket>> TICKETS = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;

    public HttpTicketOperations(RestClient client) {
        this.client = client;
    }

    @Override
    public TicketResult create(TicketRequest request) {
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                TicketResult result = client.post().uri("/internal/v1/tickets")
                        .body(request)
                        .retrieve()
                        .body(TicketResult.class);
                return result == null ? TicketResult.unavailable() : result;
            }
            catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    throw new OperationConflictException(request.operationId());
                }
                lastFailure = e;
                log.warn("Ticket service rejected attempt {} for operation {}: {}", attempt, request.operationId(),
                        e.getStatusCode());
            }
            catch (RestClientException e) {
                lastFailure = e;
                log.warn("Ticket service unreachable on attempt {} for operation {}: {}", attempt,
                        request.operationId(), e.getMessage());
            }
        }

        Optional<TicketResult> recovered = recorded(request.operationId());
        if (recovered.isPresent()) {
            log.info("Recovered the outcome of operation {} after both attempts failed to answer",
                    request.operationId());
            return recovered.get();
        }
        log.error("Ticket service unavailable for operation {}; telling the model so", request.operationId(),
                lastFailure);
        return TicketResult.unavailable();
    }

    @Override
    public Optional<TicketResult> recorded(String operationId) {
        try {
            return Optional.ofNullable(client.get().uri("/internal/v1/ticket-operations/{id}", operationId)
                    .retrieve()
                    .body(TicketResult.class));
        }
        catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
        catch (RestClientException e) {
            log.warn("Could not read the record of operation {}: {}", operationId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<SupportTicket> ticketsFor(String conversationId) {
        List<SupportTicket> tickets = client.get()
                .uri(builder -> builder.path("/internal/v1/tickets").queryParam("conversationId", conversationId).build())
                .retrieve()
                .body(TICKETS);
        return tickets == null ? List.of() : tickets;
    }
}
