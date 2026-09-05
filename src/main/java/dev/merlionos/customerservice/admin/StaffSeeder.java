package dev.merlionos.customerservice.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * The seed command for the first admin. There is no other way in: accounts are created by an
 * admin, and on a fresh database there is none. With {@code ADMIN_SEED_USERNAME} and
 * {@code ADMIN_SEED_PASSWORD} set, the process creates that admin at startup <em>if the table
 * is empty</em> and otherwise does nothing but say so. It is safe to leave set: the seed can
 * never overwrite, re-enable or reset an account, so a leaked seed password is a problem
 * only for as long as the table is empty.
 *
 * <p>Half a seed -- a username with no password, or the reverse -- refuses to start, in the
 * style of the rest of the configuration: a deploy that meant to seed and silently did not
 * would leave an admin with no way in and nothing in the log to explain why.
 */
public class StaffSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffSeeder.class);

    static final String CREATED_BY = "seed";

    private final StaffAccounts accounts;
    private final AdminProperties.Seed seed;

    public StaffSeeder(StaffAccounts accounts, AdminProperties properties) {
        this.accounts = accounts;
        this.seed = properties.seed();
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Package-private so the test can call it without an application context. Returns whether an account was created. */
    boolean seed() {
        if (!seed.configured()) {
            return false;
        }
        if (!seed.complete()) {
            throw new IllegalStateException("app.admin.seed needs both username and password "
                    + "(ADMIN_SEED_USERNAME and ADMIN_SEED_PASSWORD); one without the other seeds nothing, "
                    + "so refusing to start rather than leave no way to sign in.");
        }
        if (!accounts.isEmpty()) {
            log.info("Staff accounts exist; not seeding '{}'", StaffAccounts.normalise(seed.username()));
            return false;
        }
        try {
            StaffAccount admin = accounts.create(seed.username(), seed.password(), StaffRole.ADMIN, CREATED_BY);
            log.info("Seeded the first admin account '{}'. Unset ADMIN_SEED_* once it has signed in.", admin.username());
            return true;
        }
        catch (DuplicateStaffAccountException e) {
            // Two replicas started against the same empty table and both read it as empty; the
            // primary key let one of them in. The other has nothing left to do.
            log.info("Another replica seeded '{}' first; nothing to do", StaffAccounts.normalise(seed.username()));
            return false;
        }
    }
}
