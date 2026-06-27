package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.dto.UserDto;
import com.cpintel.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User profile endpoints")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "Get current user profile")
    public ResponseEntity<ApiResponse<UserDto.Profile>> getMe(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getProfile(userId)));
    }

    @PutMapping("/me")
    @Operation(summary = "Update current user profile")
    public ResponseEntity<ApiResponse<UserDto.Profile>> updateMe(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody UserDto.UpdateRequest req
    ) {
        return ResponseEntity.ok(ApiResponse.ok(userService.updateProfile(userId, req)));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Get dashboard summary data")
    public ResponseEntity<ApiResponse<UserDto.DashboardData>> getDashboard(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getDashboard(userId)));
    }
}
