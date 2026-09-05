package dev.merlionos.customerservice.ticket.api;

/** An operation id was reused with different input. A retry repeats the request; it does not change it. */
public class OperationConflictException extends RuntimeException {

    public OperationConflictException(String operationId) {
        super("Operation " + operationId + " was already recorded with different input");
    }
}
