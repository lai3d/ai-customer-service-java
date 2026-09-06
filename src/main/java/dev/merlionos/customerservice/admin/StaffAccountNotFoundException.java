package dev.merlionos.customerservice.admin;

/** No account by that name. A {@code 404} on the API. */
public class StaffAccountNotFoundException extends RuntimeException {

    public StaffAccountNotFoundException(String username) {
        super("No staff account named '" + username + "'");
    }
}
