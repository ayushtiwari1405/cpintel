package com.cpintel.files;

import com.cpintel.entity.mongo.PersonalFile;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.mongo.PersonalFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The rules an upload has to get past.
 *
 * All of it runs against a mocked repository: the interesting behaviour here is the
 * validation, not the persistence, and every one of these cases is a way a contestant could
 * lose their reference material — or a way someone could reach a file that is not theirs.
 */
class PersonalFileServiceTest {

    private static final long USER = 7L;
    private static final long OTHER_USER = 8L;

    private PersonalFileRepository repo;
    private PersonalFileService service;

    @BeforeEach
    void setUp() {
        repo = mock(PersonalFileRepository.class);
        service = new PersonalFileService(repo);

        ReflectionTestUtils.setField(service, "maxFileBytes", 1024);
        ReflectionTestUtils.setField(service, "maxTotalBytes", 4096L);
        ReflectionTestUtils.setField(service, "maxFiles", 3);
        ReflectionTestUtils.setField(service, "allowedExtensionsRaw", "cpp, txt, pdf, png");
        ReflectionTestUtils.invokeMethod(service, "parseAllowedExtensions");

        when(repo.listMetadata(anyLong())).thenReturn(List.of());
        when(repo.findByUserIdAndName(anyLong(), anyString())).thenReturn(Optional.empty());
        when(repo.save(any(PersonalFile.class))).thenAnswer(call -> {
            PersonalFile row = call.getArgument(0);
            if (row.getId() == null) row.setId("saved-id");
            return row;
        });
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, null, bytes);
    }

    private MockMultipartFile file(String name, String content) {
        return file(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private PersonalFile stored(String id, String name, int size) {
        return PersonalFile.builder()
            .id(id).userId(USER).name(name).sizeBytes(size)
            .uploadedAt(Instant.now()).updatedAt(Instant.now())
            .build();
    }

    @Nested
    @DisplayName("names")
    class Names {

        @Test
        @DisplayName("keeps only the base name, so an upload can never describe a path")
        void stripsDirectories() {
            FilesDto.FileMeta meta =
                service.upload(USER, file("../../etc/passwd.txt", "x"), null, false);
            assertEquals("passwd.txt", meta.name());
        }

        @Test
        @DisplayName("folds characters that would not be safe in a download header")
        void foldsUnsafeCharacters() {
            FilesDto.FileMeta meta =
                service.upload(USER, file("team\"notes;v2.txt", "x"), null, false);
            assertEquals("team_notes_v2.txt", meta.name());
        }

        @Test
        @DisplayName("refuses a name that sanitises away to nothing")
        void refusesEmptyName() {
            ApiException e = assertThrows(ApiException.class,
                () -> service.upload(USER, file("...", "x"), null, false));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {

        @Test
        @DisplayName("refuses an extension outside the allowlist")
        void extensionAllowlist() {
            ApiException e = assertThrows(ApiException.class,
                () -> service.upload(USER, file("payload.exe", "x"), null, false));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
            assertTrue(e.getMessage().contains("cpp"), "message should name what is allowed");
        }

        @Test
        @DisplayName("refuses a file over the per-file cap")
        void perFileCap() {
            ApiException e = assertThrows(ApiException.class,
                () -> service.upload(USER, file("big.txt", new byte[2048]), null, false));
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatus());
        }

        @Test
        @DisplayName("refuses an upload that would push the library over its total")
        void totalCap() {
            when(repo.listMetadata(USER)).thenReturn(List.of(
                stored("a", "a.txt", 2000), stored("b", "b.txt", 1800)));

            ApiException e = assertThrows(ApiException.class,
                () -> service.upload(USER, file("c.txt", new byte[500]), null, false));
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatus());
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("refuses a new file once the count is reached, but still allows a replace")
        void fileCount() {
            List<PersonalFile> full = new ArrayList<>(List.of(
                stored("a", "a.txt", 10), stored("b", "b.txt", 10), stored("c", "c.txt", 10)));
            when(repo.listMetadata(USER)).thenReturn(full);

            assertThrows(ApiException.class,
                () -> service.upload(USER, file("d.txt", "x"), null, false));

            when(repo.findByUserIdAndName(USER, "c.txt")).thenReturn(Optional.of(full.get(2)));
            assertDoesNotThrow(() -> service.upload(USER, file("c.txt", "x"), null, true));
        }

        @Test
        @DisplayName("discounts the file being replaced when counting the total")
        void replaceDoesNotDoubleCount() {
            PersonalFile existing = stored("a", "a.txt", 900);
            when(repo.listMetadata(USER)).thenReturn(List.of(
                existing, stored("b", "b.txt", 3000)));
            when(repo.findByUserIdAndName(USER, "a.txt")).thenReturn(Optional.of(existing));

            // 900 + 3000 + 1000 would be over 4096; without the old copy it fits.
            assertDoesNotThrow(
                () -> service.upload(USER, file("a.txt", new byte[1000]), null, true));
        }
    }

    @Nested
    @DisplayName("replacing")
    class Replacing {

        @Test
        @DisplayName("refuses a duplicate name rather than silently overwriting")
        void duplicateNeedsOptIn() {
            when(repo.findByUserIdAndName(USER, "template.cpp"))
                .thenReturn(Optional.of(stored("a", "template.cpp", 10)));

            ApiException e = assertThrows(ApiException.class,
                () -> service.upload(USER, file("template.cpp", "new"), null, false));
            assertEquals(HttpStatus.CONFLICT, e.getStatus());
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("replaces in place when asked, keeping the same file id")
        void replaceKeepsIdentity() {
            when(repo.findByUserIdAndName(USER, "template.cpp"))
                .thenReturn(Optional.of(stored("existing-id", "template.cpp", 10)));

            FilesDto.FileMeta meta =
                service.upload(USER, file("template.cpp", "#include <bits/stdc++.h>"), null, true);
            assertEquals("existing-id", meta.id());
            assertEquals(24, meta.sizeBytes());
        }
    }

    @Nested
    @DisplayName("text detection")
    class TextDetection {

        @Test
        @DisplayName("treats source as text and an image as not")
        void byExtension() {
            assertTrue(service.upload(USER, file("t.cpp", "int main(){}"), null, false).textual());
            assertFalse(service.upload(USER, file("s.png", new byte[]{1, 2, 3}), null, false)
                .textual());
        }

        @Test
        @DisplayName("distrusts the extension when the bytes are binary")
        void nulBytesWin() {
            byte[] binary = {'h', 'i', 0, 'x'};
            assertFalse(service.upload(USER, file("looks-like.txt", binary), null, false)
                .textual());
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("clips a long text file for the panel and says it did")
        void clipsPreview() {
            byte[] big = new byte[600 * 1024];
            java.util.Arrays.fill(big, (byte) 'a');
            PersonalFile row = stored("a", "notes.txt", big.length);
            row.setTextual(true);
            row.setContent(big);
            when(repo.findByIdAndUserId("a", USER)).thenReturn(Optional.of(row));

            FilesDto.FileContent content = service.content(USER, "a");
            assertTrue(content.truncated());
            assertEquals(512 * 1024, content.text().length());
            assertNotNull(content.notice());
        }

        @Test
        @DisplayName("hands back no text for a binary file, and a line saying why")
        void binaryHasNoText() {
            PersonalFile row = stored("a", "notebook.pdf", 10);
            row.setTextual(false);
            row.setContent(new byte[]{1, 2, 3});
            when(repo.findByIdAndUserId("a", USER)).thenReturn(Optional.of(row));

            FilesDto.FileContent content = service.content(USER, "a");
            assertNull(content.text());
            assertNotNull(content.notice());
        }

        @Test
        @DisplayName("another user's file id reads as missing, not as forbidden")
        void ownershipIsPartOfTheLookup() {
            when(repo.findByIdAndUserId("a", OTHER_USER)).thenReturn(Optional.empty());

            ApiException e = assertThrows(ApiException.class,
                () -> service.content(OTHER_USER, "a"));
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
    }
}
