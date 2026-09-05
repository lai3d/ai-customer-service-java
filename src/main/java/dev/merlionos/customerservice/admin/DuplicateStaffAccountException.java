package dev.merlionos.customerservice.admin;

/** The username is taken. A {@code 409} on the API. */
public class DuplicateStaffAccountException extends RuntimeException {

    public DuplicateStaffAccountException(String username) {
        super("A staff account named '" + username + "' already exists");
    }
}
