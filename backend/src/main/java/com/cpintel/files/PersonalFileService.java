package com.cpintel.files;

import com.cpintel.entity.mongo.PersonalFile;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.mongo.PersonalFileRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The user's own files, stored so they are reachable from inside a running contest.
 *
 * The need is the same one the archive answers for previous submissions: during a contest the
 * app is the only window that is open, and telling someone to go and find their template in a
 * file browser is telling them to leave. So a template, a snippets header or a scanned team
 * notebook lives here, and the contest page can show it without a round trip to anywhere.
 *
 * Every read is keyed by owner. There is no sharing model and no admin read path — an admin
 * can decide whether a contest exposes the vault at all (see {@link ContestFilePolicy}) but
 * never what is in someone's vault. Nothing here is uploaded to a judge either: CPIntel
 * submits source code, and a personal file only ever travels back to the person who put it in.
 *
 * Uploads are bounded three ways — per file, per vault and by count — because this is
 * user-controlled storage in a shared database, and a limit the user can see beforehand is
 * kinder than a failure halfway through a contest.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersonalFileService {

    /** Extensions worth rendering in the panel rather than only offering as a download. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "txt", "md", "cpp", "cc", "cxx", "c", "h", "hpp", "hxx", "java", "py", "js", "ts",
        "rs", "go", "kt", "kts", "cs", "rb", "sh", "sql", "json", "yaml", "yml", "csv", "tex",
        "in", "out", "log", "ini", "cfg", "conf", "xml", "html", "css");

    /** How much of a text file the panel gets in one response. Downloads are never clipped. */
    private static final int PREVIEW_LIMIT_BYTES = 512 * 1024;

    private static final int MAX_NAME_LENGTH = 120;

    /** Anything outside this is folded to '_' so the name is safe in a download header. */
    private static final String NAME_SAFE = "[^A-Za-z0-9 ._+()\\[\\]-]";

    private final PersonalFileRepository repo;

    @Value("${cpintel.files.max-file-bytes:2097152}")
    private int maxFileBytes;

    @Value("${cpintel.files.max-total-bytes:33554432}")
    private long maxTotalBytes;

    @Value("${cpintel.files.max-files:100}")
    private int maxFiles;

    @Value("${cpintel.files.allowed-extensions:}")
    private String allowedExtensionsRaw;

    private Set<String> allowedExtensions;

    @PostConstruct
    void parseAllowedExtensions() {
        allowedExtensions = Arrays.stream(allowedExtensionsRaw.split(","))
            .map(s -> s.trim().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ------------------------------------------------------------------ read

    public FilesDto.Vault vault(Long userId) {
        List<PersonalFile> rows = repo.listMetadata(userId);
        List<FilesDto.FileMeta> files = new ArrayList<>(rows.size());
        long used = 0;
        for (PersonalFile row : rows) {
            files.add(toMeta(row));
            used += row.getSizeBytes() == null ? 0 : row.getSizeBytes();
        }
        return new FilesDto.Vault(files, used, maxTotalBytes, maxFileBytes, maxFiles,
            List.copyOf(allowedExtensions));
    }

    /**
     * One file, decoded for the panel when that is possible.
     *
     * A binary file is not an error — it comes back with a null body and a line saying to
     * download it, because a PDF of a team notebook is a perfectly ordinary thing to keep here.
     */
    public FilesDto.FileContent content(Long userId, String fileId) {
        PersonalFile row = require(userId, fileId);
        FilesDto.FileMeta meta = toMeta(row);
        byte[] bytes = row.getContent() == null ? new byte[0] : row.getContent();

        if (!Boolean.TRUE.equals(row.getTextual())) {
            return new FilesDto.FileContent(meta, null, false,
                "This file is not text — download it to open it.");
        }

        boolean truncated = bytes.length > PREVIEW_LIMIT_BYTES;
        byte[] shown = truncated ? Arrays.copyOf(bytes, PREVIEW_LIMIT_BYTES) : bytes;
        return new FilesDto.FileContent(meta, new String(shown, StandardCharsets.UTF_8),
            truncated,
            truncated ? "Showing the first " + (PREVIEW_LIMIT_BYTES / 1024)
                + " KB. Download the file for the rest." : null);
    }

    public FilesDto.Download download(Long userId, String fileId) {
        PersonalFile row = require(userId, fileId);
        return new FilesDto.Download(row.getName(),
            row.getContentType() == null ? "application/octet-stream" : row.getContentType(),
            row.getContent() == null ? new byte[0] : row.getContent());
    }

    // ----------------------------------------------------------------- write

    /**
     * Stores an upload, replacing a same-named file only when asked to.
     *
     * Replacement is opt-in rather than automatic: a template is something people re-upload
     * often, so overwriting has to be possible, but doing it silently would mean a mistyped
     * name quietly destroys the file it collided with.
     */
    public FilesDto.FileMeta upload(Long userId, MultipartFile upload, String label,
                                    boolean replace) {
        if (upload == null || upload.isEmpty()) {
            throw ApiException.badRequest("There is no file to upload.");
        }
        if (upload.getSize() > maxFileBytes) {
            throw tooLarge("That file is " + human(upload.getSize()) + ". The limit is "
                + human(maxFileBytes) + " per file.");
        }

        String name = sanitizeName(upload.getOriginalFilename());
        String extension = extensionOf(name);
        if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(extension)) {
            throw ApiException.badRequest("Files ending ." + extension + " are not accepted. "
                + "Allowed: " + String.join(", ", allowedExtensions) + ".");
        }

        byte[] bytes;
        try {
            bytes = upload.getBytes();
        } catch (IOException e) {
            log.warn("Upload read failed for user {} ({}): {}", userId, name, e.getMessage());
            throw ApiException.badRequest("That upload could not be read. Try again.");
        }
        if (bytes.length > maxFileBytes) {
            throw tooLarge("That file is " + human(bytes.length) + ". The limit is "
                + human(maxFileBytes) + " per file.");
        }

        PersonalFile existing = repo.findByUserIdAndName(userId, name).orElse(null);
        if (existing != null && !replace) {
            throw ApiException.conflict("You already have a file called " + name
                + ". Upload it again with replace turned on, or rename the old one.");
        }
        checkQuota(userId, bytes.length, existing);

        Instant now = Instant.now();
        PersonalFile row = existing != null ? existing : PersonalFile.builder()
            .userId(userId)
            .name(name)
            .uploadedAt(now)
            .build();

        row.setLabel(trimToNull(label, 200));
        row.setContentType(contentTypeOf(extension));
        row.setContent(bytes);
        row.setSizeBytes(bytes.length);
        row.setSourceHash(sha256(bytes));
        row.setTextual(isTextual(extension, bytes));
        row.setUpdatedAt(now);

        try {
            return toMeta(repo.save(row));
        } catch (DuplicateKeyException e) {
            // Two uploads of the same name racing each other. The unique index caught it.
            throw ApiException.conflict("You already have a file called " + name + ".");
        }
    }

    public FilesDto.FileMeta rename(Long userId, String fileId, FilesDto.RenameRequest req) {
        PersonalFile row = require(userId, fileId);
        String name = sanitizeName(req.name());
        String extension = extensionOf(name);
        if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(extension)) {
            throw ApiException.badRequest("Files ending ." + extension + " are not accepted. "
                + "Allowed: " + String.join(", ", allowedExtensions) + ".");
        }
        if (!name.equals(row.getName())
            && repo.findByUserIdAndName(userId, name).isPresent()) {
            throw ApiException.conflict("You already have a file called " + name + ".");
        }

        row.setName(name);
        row.setLabel(trimToNull(req.label(), 200));
        // Renaming template.cpp to notes.pdf changes how it is served, so re-derive both.
        row.setContentType(contentTypeOf(extension));
        row.setTextual(isTextual(extension, row.getContent()));
        row.setUpdatedAt(Instant.now());
        return toMeta(repo.save(row));
    }

    public void delete(Long userId, String fileId) {
        PersonalFile row = require(userId, fileId);
        repo.delete(row);
    }

    // ---------------------------------------------------------------- helpers

    private PersonalFile require(Long userId, String fileId) {
        return repo.findByIdAndUserId(fileId, userId)
            .orElseThrow(() -> ApiException.notFound("No such file in your library."));
    }

    /** Counts the vault against both caps, discounting the row an upload is replacing. */
    private void checkQuota(Long userId, int incoming, PersonalFile replacing) {
        List<PersonalFile> rows = repo.listMetadata(userId);
        long used = 0;
        for (PersonalFile row : rows) {
            if (replacing != null && row.getId().equals(replacing.getId())) continue;
            used += row.getSizeBytes() == null ? 0 : row.getSizeBytes();
        }
        if (replacing == null && rows.size() >= maxFiles) {
            throw tooLarge("Your library is full at " + maxFiles
                + " files. Delete something to make room.");
        }
        if (used + incoming > maxTotalBytes) {
            throw tooLarge("That would put your library over " + human(maxTotalBytes)
                + " — it holds " + human(used) + " now.");
        }
    }

    /**
     * Reduces whatever the browser sent to a plain base name.
     *
     * Directory parts are dropped rather than escaped, and anything outside a conservative
     * set is folded to an underscore, so the stored name is safe to echo back in a
     * Content-Disposition header and can never describe a path.
     */
    private String sanitizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll(NAME_SAFE, "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > MAX_NAME_LENGTH) {
            String extension = extensionOf(name);
            int keep = MAX_NAME_LENGTH - extension.length() - 1;
            name = name.substring(0, Math.max(1, keep)) + "." + extension;
        }
        if (name.isBlank()) {
            throw ApiException.badRequest("That file needs a name with an extension, "
                + "for example template.cpp.");
        }
        return name;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
            ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * The type this file is served back as.
     *
     * Derived from the extension, never from what the browser declared. A stored type the
     * uploader chose is a stored type an attacker chose, and it is handed straight back to a
     * browser on download — so an unrecognised extension is bytes, and nothing more.
     */
    private String contentTypeOf(String extension) {
        if (TEXT_EXTENSIONS.contains(extension)) return "text/plain";
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    /**
     * Whether the bytes can be shown as text.
     *
     * The extension proposes and the bytes decide: a NUL byte anywhere means something
     * binary arrived under a text-looking name, and rendering it would fill the panel with
     * replacement characters. The browser's declared type gets no say — it is uploader-
     * controlled, and the extension is already what the allowlist was checked against.
     */
    private boolean isTextual(String extension, byte[] bytes) {
        if (!TEXT_EXTENSIONS.contains(extension) || bytes == null) return false;
        int scan = Math.min(bytes.length, 8192);
        for (int i = 0; i < scan; i++) {
            if (bytes[i] == 0) return false;
        }
        return true;
    }

    private FilesDto.FileMeta toMeta(PersonalFile row) {
        return new FilesDto.FileMeta(
            row.getId(), row.getName(), row.getLabel(), row.getContentType(),
            row.getSizeBytes() == null ? 0 : row.getSizeBytes(),
            Boolean.TRUE.equals(row.getTextual()), row.getSourceHash(),
            row.getUploadedAt(), row.getUpdatedAt());
    }

    private ApiException tooLarge(String message) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", message);
    }

    private String trimToNull(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private String human(long bytes) {
        if (bytes >= 1024 * 1024) return Math.round(bytes / (1024.0 * 1024.0) * 10) / 10.0 + " MB";
        if (bytes >= 1024) return Math.round(bytes / 1024.0) + " KB";
        return bytes + " bytes";
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }
}
