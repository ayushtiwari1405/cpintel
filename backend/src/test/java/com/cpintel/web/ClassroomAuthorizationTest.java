package com.cpintel.web;

import com.cpintel.classrooms.ClassroomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Only a superadmin creates, archives and staffs a classroom. An admin works inside the ones
 * they were added to, which the classroom checks themselves cover.
 */
@WebMvcTest(controllers = com.cpintel.controller.AdminClassroomController.class)
class ClassroomAuthorizationTest extends AuthorizationTestBase {

    @MockBean private ClassroomService classrooms;
    @MockBean private com.cpintel.evaluation.ClassroomTaService tas;

    private static final String BODY =
        "{\"name\":\"DSA\",\"domjudgeUrl\":\"https://judge.example.edu\"}";

    @Test
    @DisplayName("an admin cannot create a classroom")
    void adminCannotCreate() throws Exception {
        mvc.perform(post("/api/v1/admin/classrooms").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a superadmin can")
    void superAdminCreates() throws Exception {
        mvc.perform(post("/api/v1/admin/classrooms").with(asSuperAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an admin cannot archive a classroom or change who runs it")
    void adminCannotArchiveOrStaff() throws Exception {
        mvc.perform(post("/api/v1/admin/classrooms/1/archive").with(asAdmin()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/classrooms/1/staff").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":7}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/classrooms/1/staff/7").with(asAdmin()))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin can still list and edit the classrooms they run")
    void adminWorksInside() throws Exception {
        mvc.perform(get("/api/v1/admin/classrooms").with(asAdmin()))
            .andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/classrooms/1").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk());
    }
}
