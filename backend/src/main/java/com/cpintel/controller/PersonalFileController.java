package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.files.FilesDto;
import com.cpintel.files.PersonalFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * The user's own reference material — templates, snippet headers, a team notebook.
 *
 * Managing the library is always allowed; it is the user's own storage and has nothing to do
 * with any contest. Reading it *from inside* a contest goes through the compete routes
 * instead, which is where an admin's per-contest rule is enforced.
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "Personal files you can open during a contest")
@SecurityRequirement(name = "bearerAuth")
public class PersonalFileController {

    private final PersonalFileService files;

    @GetMapping
    @Operation(summary = "Your library, with the limits it is held to")
    public ResponseEntity<ApiResponse<FilesDto.Vault>> vault(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(files.vault(userId)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a file. Replacing a same-named file is opt-in")
    public ResponseEntity<ApiResponse<FilesDto.FileMeta>> upload(
        @AuthenticationPrincipal Long userId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String label,
        @RequestParam(defaultValue = "false") boolean replace) {
        return ResponseEntity.ok(ApiResponse.ok(files.upload(userId, file, label, replace)));
    }

    @GetMapping("/{fileId}")
    @Operation(summary = "Open a file. Text comes back inline; anything else is download-only")
    public ResponseEntity<ApiResponse<FilesDto.FileContent>> content(
        @AuthenticationPrincipal Long userId,
        @PathVariable String fileId) {
        return ResponseEntity.ok(ApiResponse.ok(files.content(userId, fileId)));
    }

    @GetMapping("/{fileId}/download")
    @Operation(summary = "The raw bytes, never clipped")
    public ResponseEntity<byte[]> download(
        @AuthenticationPrincipal Long userId,
        @PathVariable String fileId) {
        return asDownload(files.download(userId, fileId));
    }

    @PatchMapping("/{fileId}")
    @Operation(summary = "Rename a file or change its note")
    public ResponseEntity<ApiResponse<FilesDto.FileMeta>> rename(
        @AuthenticationPrincipal Long userId,
        @PathVariable String fileId,
        @Valid @RequestBody FilesDto.RenameRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(files.rename(userId, fileId, req)));
    }

    @DeleteMapping("/{fileId}")
    @Operation(summary = "Delete a file")
    public ResponseEntity<ApiResponse<Void>> delete(
        @AuthenticationPrincipal Long userId,
        @PathVariable String fileId) {
        files.delete(userId, fileId);
        return ResponseEntity.ok(ApiResponse.message("File deleted"));
    }

    /**
     * Shared by this controller and the contest-scoped one, so a file downloaded mid-contest
     * arrives exactly as it would outside one.
     */
    static ResponseEntity<byte[]> asDownload(FilesDto.Download file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(file.name(), StandardCharsets.UTF_8).build());
        headers.setContentLength(file.bytes().length);
        return new ResponseEntity<>(file.bytes(), headers, HttpStatus.OK);
    }
}
