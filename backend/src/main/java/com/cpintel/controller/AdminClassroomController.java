package com.cpintel.controller;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.classrooms.ClassroomsDto;
import com.cpintel.common.ApiResponse;
import com.cpintel.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/classrooms")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
// Any admin may work inside the classrooms they run; creating, archiving and staffing a
// classroom is the superadmin's alone.
@Tag(name = "Admin", description = "Classrooms, each with its own DOMjudge")
@SecurityRequirement(name = "bearerAuth")
public class AdminClassroomController {

    private final ClassroomService classrooms;

    @GetMapping
    @Operation(summary = "The classrooms you run (every one, for a superadmin)")
    public ResponseEntity<ApiResponse<List<ClassroomsDto.ClassroomSummary>>> list(
        @AuthenticationPrincipal Long adminId) {
        return ResponseEntity.ok(ApiResponse.ok(classrooms.list(adminId)));
    }

    @PostMapping
    @Operation(summary = "Create a classroom on a DOMjudge instance")
    @PreAuthorize(Roles.HAS_SUPER)
    public ResponseEntity<ApiResponse<ClassroomsDto.ClassroomSummary>> create(
        @AuthenticationPrincipal Long adminId,
        @Valid @RequestBody ClassroomsDto.ClassroomRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(classrooms.create(adminId, req, httpReq)));
    }

    @GetMapping("/{classroomId}")
    @Operation(summary = "One classroom")
    public ResponseEntity<ApiResponse<ClassroomsDto.ClassroomSummary>> detail(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId) {
        return ResponseEntity.ok(ApiResponse.ok(classrooms.detail(adminId, classroomId)));
    }

    @PutMapping("/{classroomId}")
    @Operation(summary = "Rename a classroom or change its judge settings")
    public ResponseEntity<ApiResponse<ClassroomsDto.ClassroomSummary>> update(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long classroomId,
        @Valid @RequestBody ClassroomsDto.ClassroomRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            classrooms.update(adminId, classroomId, req, httpReq)));
    }

    @PostMapping("/{classroomId}/archive")
    @Operation(summary = "Archive a classroom")
    @PreAuthorize(Roles.HAS_SUPER)
    public ResponseEntity<ApiResponse<Void>> archive(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId,
        HttpServletRequest httpReq) {
        classrooms.archive(adminId, classroomId, false, httpReq);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{classroomId}/restore")
    @Operation(summary = "Bring an archived classroom back")
    @PreAuthorize(Roles.HAS_SUPER)
    public ResponseEntity<ApiResponse<Void>> restore(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId,
        HttpServletRequest httpReq) {
        classrooms.archive(adminId, classroomId, true, httpReq);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{classroomId}/check")
    @Operation(summary = "Check that the classroom's judge answers")
    public ResponseEntity<ApiResponse<ClassroomsDto.JudgeCheck>> check(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId) {
        return ResponseEntity.ok(ApiResponse.ok(classrooms.checkJudge(adminId, classroomId)));
    }

    @GetMapping("/{classroomId}/members")
    @Operation(summary = "Students enrolled in the classroom")
    public ResponseEntity<ApiResponse<List<ClassroomsDto.Member>>> members(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId) {
        return ResponseEntity.ok(ApiResponse.ok(classrooms.members(adminId, classroomId)));
    }

    @PostMapping("/{classroomId}/members")
    @Operation(summary = "Enrol a student")
    public ResponseEntity<ApiResponse<ClassroomsDto.Member>> addMember(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId,
        @Valid @RequestBody ClassroomsDto.MemberRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            classrooms.addMember(adminId, classroomId, req.userId(), httpReq)));
    }

    @DeleteMapping("/{classroomId}/members/{userId}")
    @Operation(summary = "Take a student out of the classroom, its teams and its judge login")
    public ResponseEntity<ApiResponse<Void>> removeMember(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId,
        @PathVariable Long userId, HttpServletRequest httpReq) {
        classrooms.removeMember(adminId, classroomId, userId, httpReq);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/{classroomId}/staff")
    @Operation(summary = "Admins who run the classroom")
    public ResponseEntity<ApiResponse<List<ClassroomsDto.StaffMember>>> staff(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId) {
        return ResponseEntity.ok(ApiResponse.ok(classrooms.staff(adminId, classroomId)));
    }

    @PostMapping("/{classroomId}/staff")
    @Operation(summary = "Let another admin run the classroom")
    @PreAuthorize(Roles.HAS_SUPER)
    public ResponseEntity<ApiResponse<Void>> addStaff(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId,
        @Valid @RequestBody ClassroomsDto.StaffRequest req, HttpServletRequest httpReq) {
        classrooms.addStaff(adminId, classroomId, req.userId(), httpReq);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping("/{classroomId}/staff/{userId}")
    @Operation(summary = "Stop an admin running the classroom")
    @PreAuthorize(Roles.HAS_SUPER)
    public ResponseEntity<ApiResponse<Void>> removeStaff(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId,
        @PathVariable Long userId, HttpServletRequest httpReq) {
        classrooms.removeStaff(adminId, classroomId, userId, httpReq);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
