package com.cpintel.controller;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.classrooms.ClassroomsDto;
import com.cpintel.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/classrooms")
@RequiredArgsConstructor
@Tag(name = "Classrooms", description = "The classrooms you are enrolled in")
@SecurityRequirement(name = "bearerAuth")
public class ClassroomController {

    private final ClassroomService classrooms;

    @GetMapping("/mine")
    @Operation(summary = "Your classrooms, and whether you can submit in each")
    public ResponseEntity<ApiResponse<List<ClassroomsDto.MyClassroom>>> mine(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(classrooms.mine(userId)));
    }
}
