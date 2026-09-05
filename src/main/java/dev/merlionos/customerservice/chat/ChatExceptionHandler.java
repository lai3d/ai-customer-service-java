package dev.merlionos.customerservice.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.merlionos.customerservice.cost.ConversationBudgetExceededException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns model-provider failures into responses a client can act on.
 *
 * <p>Spring AI raises {@link TransientAiException} for conditions worth retrying (rate
 * limits, overload, upstream 5xx) and {@link NonTransientAiException} for everything else
 * (a bad API key, a rejected request). Without this, both surface as a bare HTTP 500 with
 * an empty body, which tells a caller nothing about whether retrying is worth its time.
 *
 * <p>Upstream messages can carry request details, so they are logged rather than returned.
 */
@RestControllerAdvice(assignableTypes = ChatController.class)
class ChatExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatExceptionHandler.class);

    @ExceptionHandler(ConversationBudgetExceededException.class)
    ProblemDetail handleBudgetExceeded(ConversationBudgetExceededException exception) {
        // Not an error condition so much as a policy one, and the honest response to a
        // conversation this long is a person rather than more model calls.
        log.info("Token budget exhausted for conversation {}", exception.conversationId());

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Conversation limit reached");
        problem.setDetail("This conversation has reached its limit. Start a new one, or ask to "
                + "be put through to a human agent.");
        return problem;
    }

    @ExceptionHandler(ConversationBusyException.class)
    ProblemDetail handleBusy(ConversationBusyException exception) {
        // Two turns on one conversation would interleave its history; the second is refused
        // before anything is written, and the client can retry once the first has finished.
        log.info("Refused an overlapping turn on conversation {}", exception.conversationId());

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Conversation busy");
        problem.setDetail("This conversation is still answering a previous message. Wait for it "
                + "to finish, then send again.");
        return problem;
    }

    @ExceptionHandler(TransientAiException.class)
    ProblemDetail handleTransient(TransientAiException exception) {
        log.warn("Transient failure from the model provider", exception);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Assistant temporarily unavailable");
        problem.setDetail("The assistant is busy right now. Please try again in a moment.");
        return problem;
    }

    @ExceptionHandler(NonTransientAiException.class)
    ProblemDetail handleNonTransient(NonTransientAiException exception) {
        log.error("Non-transient failure from the model provider -- check credentials and request options",
                exception);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setTitle("Assistant unavailable");
        problem.setDetail("The assistant could not be reached. This has been logged for investigation.");
        return problem;
    }
}
