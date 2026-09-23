package com.cpintel.web;

import com.cpintel.admin.AdminDto;
import com.cpintel.admin.AdminUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The split that the three-tier role model exists to enforce.
 *
 * <p>An ADMIN runs the console, and may create ordinary users — somebody running a contest has
 * to be able to add the people sitting it. Only a SUPER_ADMIN may change what somebody else
 * <em>is</em>, because a role that can hand out privilege can hand itself more of it, and that
 * has to sit above the tier that merely uses the console.
 *
 * <p>Creating an ADMIN is the same escalation spelled differently, so it is reserved too — but
 * that check is on the <em>role being created</em> and therefore lives in the service rather
 * than on the route. {@code AdminUserServiceTest} is where it is pinned; this file can only see
 * that the route itself is open to both tiers.
 *
 * <p>Until this file existed the rule was verified by hand once and then guarded by nothing.
 */
@WebMvcTest(controllers = com.cpintel.controller.AdminUserController.class)
class AdminUserAuthorizationTest extends AuthorizationTestBase {

    @MockBean
    private AdminUserService users;

    @MockBean
    private com.cpintel.events.EventAnalyticsService analytics;

    private static final String ROLE_BODY   = "{\"role\":\"ADMIN\"}";
    private static final String CREATE_BODY = """
        {"username":"newbie","email":"newbie@example.com","password":"password123","role":"USER"}
        """;

    private AdminDto.UserRow row() {
        return new AdminDto.UserRow(1L, "someone", "someone@example.com", null,
            "USER", true, true, Instant.now(), null, null);
    }

    @Nested
    @DisplayName("Reading the console")
    class Reading {

        @Test
        @DisplayName("an ordinary user is refused")
        void userRefused() throws Exception {
            mvc.perform(get("/api/v1/admin/users").with(asUser()))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an admin may read it")
        void adminAllowed() throws Exception {
            when(users.list(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AdminDto.UserPage(java.util.List.of(), 0, 25, 0L, 0));

            mvc.perform(get("/api/v1/admin/users").with(asAdmin()))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a super admin may read it — the role hierarchy is doing its job")
        void superAdminAllowed() throws Exception {
            when(users.list(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AdminDto.UserPage(java.util.List.of(), 0, 25, 0L, 0));

            mvc.perform(get("/api/v1/admin/users").with(asSuperAdmin()))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an anonymous caller gets 401, not 403")
        void anonymousUnauthorized() throws Exception {
            mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Assigning a role")
    class AssigningRoles {

        @Test
        @DisplayName("an ordinary user is refused")
        void userRefused() throws Exception {
            mvc.perform(put("/api/v1/admin/users/7/role").with(asUser())
                    .contentType(MediaType.APPLICATION_JSON).content(ROLE_BODY))
                .andExpect(status().isForbidden());
            verifyNoInteractions(users);
        }

        @Test
        @DisplayName("an admin is refused — this is the whole point of the tier")
        void adminRefused() throws Exception {
            mvc.perform(put("/api/v1/admin/users/7/role").with(asAdmin())
                    .contentType(MediaType.APPLICATION_JSON).content(ROLE_BODY))
                .andExpect(status().isForbidden());
            verifyNoInteractions(users);
        }

        @Test
        @DisplayName("a super admin may do it")
        void superAdminAllowed() throws Exception {
            when(users.changeRole(anyLong(), anyLong(), any(), any())).thenReturn(row());

            mvc.perform(put("/api/v1/admin/users/7/role").with(asSuperAdmin())
                    .contentType(MediaType.APPLICATION_JSON).content(ROLE_BODY))
                .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Creating an account")
    class CreatingAccounts {

        @Test
        @DisplayName("an ordinary user is refused")
        void userRefused() throws Exception {
            mvc.perform(post("/api/v1/admin/users").with(asUser())
                    .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
            verifyNoInteractions(users);
        }

        @Test
        @DisplayName("an admin may create an ordinary user, and the service is told their tier")
        void adminAllowedForPlainUser() throws Exception {
            when(users.createUser(anyLong(), any(), anyBoolean(), any())).thenReturn(row());

            mvc.perform(post("/api/v1/admin/users").with(asAdmin())
                    .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());

            // false, not merely "some boolean": the service refuses an ADMIN on the strength of
            // this flag, so a route that passed true would hand every admin the higher tier.
            verify(users).createUser(anyLong(), any(), eq(false), any());
        }

        @Test
        @DisplayName("a super admin is reported as one, so the service lets them create an ADMIN")
        void superAdminTierIsPassedThrough() throws Exception {
            when(users.createUser(anyLong(), any(), anyBoolean(), any())).thenReturn(row());

            mvc.perform(post("/api/v1/admin/users").with(asSuperAdmin())
                    .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());

            verify(users).createUser(anyLong(), any(), eq(true), any());
        }

        @Test
        @DisplayName("a super admin may do it, and gets 201")
        void superAdminAllowed() throws Exception {
            when(users.createUser(anyLong(), any(), anyBoolean(), any())).thenReturn(row());

            mvc.perform(post("/api/v1/admin/users").with(asSuperAdmin())
                    .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("Deactivating an account — an ordinary admin's job")
    class Deactivating {

        @Test
        @DisplayName("an admin may do it, unlike role assignment")
        void adminAllowed() throws Exception {
            when(users.setActive(anyLong(), anyLong(), any(), anyBoolean(), any()))
                .thenReturn(row());

            mvc.perform(put("/api/v1/admin/users/7/active").with(asAdmin())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an ordinary user is still refused")
        void userRefused() throws Exception {
            mvc.perform(put("/api/v1/admin/users/7/active").with(asUser())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        }
    }
}
