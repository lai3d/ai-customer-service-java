package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Managing accounts over the API: disabling, a role change and a password reset, each
 * ending the account's sessions in Postgres; anyone changing their own password with the
 * current one; the rules that protect the last admin and the caller's own access, refused
 * and recorded; and support kept out. Same context as {@link AdminLoginTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminStaffApiTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffAccounts accounts;

    @BeforeEach
    void anAdminAndASupportMember() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        accounts.create("root", "first-admin-password", StaffRole.ADMIN, StaffSeeder.CREATED_BY);
        accounts.create("sam", "support-password-1", StaffRole.SUPPORT, "root");
    }

    private List<Map<String, Object>> audit() {
        return jdbc.queryForList("SELECT actor, action, target, detail FROM admin_audit ORDER BY id");
    }

    @Test
    @DisplayName("disabling an account ends its sessions and its logins with the usual sentence; enabling lets it back in")
    void disableAndEnable() throws Exception {
        AdminBrowser admin = AdminBrowser.signedIn(port, "root", "first-admin-password");
        AdminBrowser sam = AdminBrowser.signedIn(port, "sam", "support-password-1");
        assertThat(sam.get("/admin/api/me").statusCode()).isEqualTo(200);

        HttpResponse<String> disabled = admin.postJson("/admin/api/staff/Sam/enabled", "{\"enabled\":false}");
        assertThat(disabled.statusCode()).isEqualTo(200);
        assertThat(disabled.body()).contains("\"username\":\"sam\"", "\"enabled\":false");
        assertThat(sam.get("/admin/api/me").statusCode()).as("the signed-in browser is out at once").isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = 'sam'", Integer.class)).isZero();

        AdminBrowser again = new AdminBrowser(port);
        again.get("/admin/api/csrf");
        HttpResponse<String> refused = again.login("sam", "support-password-1");
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.body()).as("a disabled account reads like a wrong password").contains("Wrong username or password");

        assertThat(admin.postJson("/admin/api/staff/sam/enabled", "{\"enabled\":true}").statusCode()).isEqualTo(200);
        assertThat(AdminBrowser.signedIn(port, "sam", "support-password-1").get("/admin/api/me").statusCode()).isEqualTo(200);
        assertThat(audit()).extracting(row -> row.get("action") + " " + row.get("target"))
                .containsExactly("account_disabled sam", "account_enabled sam");
        assertThat(admin.postJson("/admin/api/staff/sam/enabled", "{}").statusCode()).isEqualTo(400);
        assertThat(admin.postJson("/admin/api/staff/nobody/enabled", "{\"enabled\":false}").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a role change ends the account's sessions; the next sign-in carries the new role")
    void roleChange() throws Exception {
        AdminBrowser admin = AdminBrowser.signedIn(port, "root", "first-admin-password");
        AdminBrowser sam = AdminBrowser.signedIn(port, "sam", "support-password-1");

        HttpResponse<String> promoted = admin.postJson("/admin/api/staff/sam/role", "{\"role\":\"admin\"}");
        assertThat(promoted.statusCode()).isEqualTo(200);
        assertThat(promoted.body()).contains("\"role\":\"admin\"");
        assertThat(sam.get("/admin/api/me").statusCode()).as("the session signed in as support is gone").isEqualTo(401);
        assertThat(AdminBrowser.signedIn(port, "sam", "support-password-1").get("/admin/api/me").body())
                .isEqualTo("{\"username\":\"sam\",\"role\":\"admin\"}");
        assertThat(audit()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("actor", "root").containsEntry("action", "role_changed").containsEntry("target", "sam");
            assertThat(row).containsEntry("detail", "support -> admin");
        });
        assertThat(admin.postJson("/admin/api/staff/sam/role", "{\"role\":\"owner\"}").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a password reset makes the old password fail and the new one work, and keeps only the resetting admin's own session")
    void passwordReset() throws Exception {
        AdminBrowser admin = AdminBrowser.signedIn(port, "root", "first-admin-password");
        AdminBrowser sam = AdminBrowser.signedIn(port, "sam", "support-password-1");

        assertThat(admin.postJson("/admin/api/staff/sam/password", "{\"password\":\"short\"}").statusCode()).isEqualTo(400);
        assertThat(admin.postJson("/admin/api/staff/sam/password", "{\"password\":\"a-new-support-password\"}").statusCode()).isEqualTo(204);
        assertThat(sam.get("/admin/api/me").statusCode()).as("the session the old password opened is gone").isEqualTo(401);
        AdminBrowser old = new AdminBrowser(port);
        old.get("/admin/api/csrf");
        assertThat(old.login("sam", "support-password-1").statusCode()).isEqualTo(401);
        assertThat(AdminBrowser.signedIn(port, "sam", "a-new-support-password").get("/admin/api/me").statusCode()).isEqualTo(200);

        AdminBrowser rootElsewhere = AdminBrowser.signedIn(port, "root", "first-admin-password");
        assertThat(admin.postJson("/admin/api/staff/root/password", "{\"password\":\"a-new-admin-password\"}").statusCode()).isEqualTo(204);
        assertThat(admin.get("/admin/api/me").statusCode()).as("the admin who reset their own password stays signed in here").isEqualTo(200);
        assertThat(rootElsewhere.get("/admin/api/me").statusCode()).as("and is signed out everywhere else").isEqualTo(401);
        assertThat(audit()).extracting(row -> row.get("action") + " " + row.get("target"))
                .containsExactly("password_reset sam", "password_reset root");
        assertThat(jdbc.queryForList("SELECT password_hash FROM staff_account", String.class))
                .allSatisfy(hash -> assertThat(hash).startsWith("{bcrypt}"));
    }

    @Test
    @DisplayName("anyone signed in changes their own password with the current one; the old fails, the new works, and only the session it was done from survives")
    void changeOwnPassword() throws Exception {
        AdminBrowser sam = AdminBrowser.signedIn(port, "sam", "support-password-1");
        AdminBrowser samElsewhere = AdminBrowser.signedIn(port, "sam", "support-password-1");
        AdminBrowser admin = AdminBrowser.signedIn(port, "root", "first-admin-password");

        HttpResponse<String> changed = sam.postJson("/admin/api/me/password",
                "{\"currentPassword\":\"support-password-1\",\"newPassword\":\"a-password-of-my-own\"}");
        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(sam.get("/admin/api/me").statusCode()).as("the session the change was made from stays").isEqualTo(200);
        assertThat(samElsewhere.get("/admin/api/me").statusCode()).as("the other one is gone").isEqualTo(401);
        assertThat(admin.get("/admin/api/me").statusCode()).as("nobody else's session is touched").isEqualTo(200);
        AdminBrowser old = new AdminBrowser(port);
        old.get("/admin/api/csrf");
        assertThat(old.login("sam", "support-password-1").statusCode()).isEqualTo(401);
        assertThat(AdminBrowser.signedIn(port, "sam", "a-password-of-my-own").get("/admin/api/me").statusCode()).isEqualTo(200);
        assertThat(audit()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("actor", "sam").containsEntry("action", "password_changed").containsEntry("target", "sam");
            assertThat(row.get("detail")).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT password_hash FROM staff_account WHERE username = 'sam'", String.class))
                .startsWith("{bcrypt}");
    }

    @Test
    @DisplayName("a wrong current password, a short new one and a new one equal to the current are refused, and nothing changes")
    void changeOwnPasswordRefusals() throws Exception {
        AdminBrowser sam = AdminBrowser.signedIn(port, "sam", "support-password-1");
        AdminBrowser samElsewhere = AdminBrowser.signedIn(port, "sam", "support-password-1");

        HttpResponse<String> wrong = sam.postJson("/admin/api/me/password",
                "{\"currentPassword\":\"not-my-password-1\",\"newPassword\":\"a-password-of-my-own\"}");
        assertThat(wrong.statusCode()).isEqualTo(422);
        assertThat(wrong.body()).contains("current password is wrong");
        assertThat(sam.postJson("/admin/api/me/password",
                "{\"currentPassword\":\"support-password-1\",\"newPassword\":\"short\"}").statusCode()).isEqualTo(400);
        HttpResponse<String> same = sam.postJson("/admin/api/me/password",
                "{\"currentPassword\":\"support-password-1\",\"newPassword\":\"support-password-1\"}");
        assertThat(same.statusCode()).isEqualTo(422);
        assertThat(same.body()).contains("must differ");
        assertThat(sam.postJson("/admin/api/me/password", "{}").statusCode()).isEqualTo(400);

        assertThat(samElsewhere.get("/admin/api/me").statusCode()).as("a refused change ends no session").isEqualTo(200);
        assertThat(AdminBrowser.signedIn(port, "sam", "support-password-1").get("/admin/api/me").statusCode())
                .as("the password is what it was").isEqualTo(200);
        assertThat(audit()).extracting(row -> row.get("actor") + " " + row.get("action") + " " + row.get("target") + ": " + row.get("detail"))
                .containsExactly("sam refused sam: The current password is wrong",
                        "sam refused sam: The new password must differ from the current one");

        AdminBrowser anonymous = new AdminBrowser(port);
        anonymous.get("/admin/api/csrf");
        assertThat(anonymous.postJson("/admin/api/me/password",
                "{\"currentPassword\":\"support-password-1\",\"newPassword\":\"a-password-of-my-own\"}").statusCode())
                .as("not signed in: nothing to change").isEqualTo(401);
    }

    @Test
    @DisplayName("your own access is not yours to remove, and the last enabled admin cannot be disabled or demoted; each refusal is recorded")
    void rulesAreRefusedAndRecorded() throws Exception {
        AdminBrowser admin = AdminBrowser.signedIn(port, "root", "first-admin-password");

        HttpResponse<String> self = admin.postJson("/admin/api/staff/root/enabled", "{\"enabled\":false}");
        assertThat(self.statusCode()).isEqualTo(422);
        assertThat(self.body()).contains("your own account");
        assertThat(admin.postJson("/admin/api/staff/root/role", "{\"role\":\"support\"}").statusCode()).isEqualTo(422);
        assertThat(admin.get("/admin/api/me").statusCode()).as("nothing happened to the caller").isEqualTo(200);

        // A second admin, who then tries to remove the first while being the one who would remain
        accounts.create("kim", "second-admin-password", StaffRole.ADMIN, "root");
        AdminBrowser kim = AdminBrowser.signedIn(port, "kim", "second-admin-password");
        assertThat(kim.postJson("/admin/api/staff/root/role", "{\"role\":\"support\"}").statusCode()).as("two admins: allowed").isEqualTo(200);
        AdminBrowser rootAgain = AdminBrowser.signedIn(port, "root", "first-admin-password");
        assertThat(rootAgain.get("/admin/api/staff").statusCode()).as("root is support now").isEqualTo(403);
        HttpResponse<String> last = kim.postJson("/admin/api/staff/root/enabled", "{\"enabled\":false}");
        assertThat(last.statusCode()).as("disabling a support account is fine").isEqualTo(200);
        assertThat(kim.postJson("/admin/api/staff/root/role", "{\"role\":\"admin\"}").statusCode())
                .as("a disabled account can be made admin again").isEqualTo(200);
        assertThat(kim.postJson("/admin/api/staff/root/enabled", "{\"enabled\":true}").statusCode()).isEqualTo(200);
        assertThat(rootAgain.get("/admin/api/me").statusCode()).as("its old session did not come back").isEqualTo(401);

        assertThat(audit()).filteredOn(row -> row.get("action").equals("refused"))
                .extracting(row -> row.get("actor") + " " + row.get("target") + ": " + row.get("detail"))
                .containsExactly("root root: You cannot disable your own account", "root root: You cannot change your own role",
                        "root GET /admin/api/staff: Access Denied");
    }

    @Test
    @DisplayName("support cannot manage accounts, and the refusal is recorded")
    void supportIsKeptOut() throws Exception {
        AdminBrowser sam = AdminBrowser.signedIn(port, "sam", "support-password-1");
        assertThat(sam.postJson("/admin/api/staff/root/enabled", "{\"enabled\":false}").statusCode()).isEqualTo(403);
        assertThat(sam.postJson("/admin/api/staff/sam/role", "{\"role\":\"admin\"}").statusCode()).isEqualTo(403);
        assertThat(sam.postJson("/admin/api/staff/root/password", "{\"password\":\"twelve-characters\"}").statusCode()).isEqualTo(403);
        assertThat(accounts.credential("root").orElseThrow().enabled()).isTrue();
        assertThat(audit()).hasSize(3).allSatisfy(row -> assertThat(row).containsEntry("actor", "sam").containsEntry("action", "refused"));
    }
}
