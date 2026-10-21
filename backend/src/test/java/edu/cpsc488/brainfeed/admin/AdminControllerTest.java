package edu.cpsc488.brainfeed.admin;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.auth.User;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Tests the input checks and ban rules in AdminController. The endpoints and SQL need a database,
// so they'll be covered by the API tests later.
class AdminControllerTest {

    private static final User ADMIN = new User(1, "admin@sru.edu", "testadmin", OffsetDateTime.now(), true);

    private static AdminRepository.AdminUser target(long id, boolean admin) {
        return new AdminRepository.AdminUser(id, "testuser", "test@sru.edu", OffsetDateTime.now(), admin, null, null);
    }

    private static void assertBadRequest(Runnable code) {
        ApiException e = assertThrows(ApiException.class, code::run);
        assertEquals(400, e.status());
    }

    @Test
    void guestsGetNotLoggedIn() {
        ApiException e = assertThrows(ApiException.class, () -> AdminController.checkAdmin(Optional.empty()));
        assertEquals(401, e.status());
    }

    @Test
    void regularUsersAreForbidden() {
        User regular = new User(2, "test@sru.edu", "testuser", OffsetDateTime.now(), false);
        ApiException e = assertThrows(ApiException.class, () -> AdminController.checkAdmin(Optional.of(regular)));
        assertEquals(403, e.status());
    }

    @Test
    void adminsAreLetThrough() {
        assertEquals(ADMIN, AdminController.checkAdmin(Optional.of(ADMIN)));
    }

    @Test
    void adminCanBanARegularUser() {
        assertDoesNotThrow(() -> AdminController.checkCanBan(ADMIN, target(2, false)));
    }

    @Test
    void adminCantBanThemselves() {
        // otherwise the last admin could lock everyone out of the admin page
        assertBadRequest(() -> AdminController.checkCanBan(ADMIN, target(1, true)));
    }

    @Test
    void adminCantBanAnotherAdmin() {
        assertBadRequest(() -> AdminController.checkCanBan(ADMIN, target(3, true)));
    }

    @Test
    void reasonIsRequiredAndTrimmed() {
        assertEquals("spam", AdminController.checkReason("  spam  "));
        assertBadRequest(() -> AdminController.checkReason(null));
        assertBadRequest(() -> AdminController.checkReason("   "));
    }

    @Test
    void reasonCantBeTooLongOrHaveNewlines() {
        assertEquals("a".repeat(200), AdminController.checkReason("a".repeat(200)));
        assertBadRequest(() -> AdminController.checkReason("a".repeat(201)));
        // a newline could be used to fake extra lines in the logs
        assertBadRequest(() -> AdminController.checkReason("spam\nevent=login outcome=success"));
    }

    @Test
    void readsOnlyPositiveUserIds() {
        assertEquals(42, AdminController.parseUserId("42"));
        assertBadRequest(() -> AdminController.parseUserId("0"));
        assertBadRequest(() -> AdminController.parseUserId("-1"));
        assertBadRequest(() -> AdminController.parseUserId("abc"));
        assertBadRequest(() -> AdminController.parseUserId("1 OR 1=1"));
        assertBadRequest(() -> AdminController.parseUserId(null));
    }

    @Test
    void searchMatchesAnywhere() {
        assertEquals("%testuser%", AdminController.likePattern(" testuser "));
        assertEquals("%%", AdminController.likePattern(null));
        assertEquals("%%", AdminController.likePattern(""));
    }

    @Test
    void searchWildcardsAreTreatedAsPlainText() {
        // without escaping, searching "%" would match every user
        assertEquals("%\\%%", AdminController.likePattern("%"));
        assertEquals("%test\\_user%", AdminController.likePattern("test_user"));
        assertEquals("%a\\\\b%", AdminController.likePattern("a\\b"));
    }

    @Test
    void searchCantBeTooLong() {
        assertBadRequest(() -> AdminController.likePattern("a".repeat(101)));
    }
}
