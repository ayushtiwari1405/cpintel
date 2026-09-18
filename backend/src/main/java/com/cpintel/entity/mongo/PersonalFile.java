package com.cpintel.entity.mongo;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * A file the user uploaded for their own use — a template, a snippets header, a printed-out
 * team notebook, a page of formulae.
 *
 * ICPC-style rules let a contestant bring their own reference material into the room, and
 * that is exactly what this is: material the user already owns, kept where they can reach it
 * without leaving a locked-down contest page. CPIntel never sends any of it anywhere; it is
 * read back to the person who uploaded it and to nobody else.
 *
 * The bytes live in the document rather than in GridFS because these are small by
 * construction — the per-file cap is well under Mongo's 16MB document limit, and a single
 * round trip beats a chunked read for something the UI opens in a side panel. Listing never
 * touches the content field (see {@code PersonalFileRepository}), so a vault of large PDFs
 * still lists instantly.
 */
@Document(collection = "personal_files")
@CompoundIndexes({
    // The vault listing, and the name-collision check that guards an upload.
    @CompoundIndex(name = "idx_files_user_name", def = "{'userId': 1, 'name': 1}", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonalFile {

    @Id
    private String id;

    @Indexed
    @Field("userId")
    private Long userId;

    /** Sanitised base name, including extension. Unique per user. */
    @Field("name")
    private String name;

    /** The user's own note about what this is. Optional. */
    @Field("label")
    private String label;

    @Field("contentType")
    private String contentType;

    @Field("sizeBytes")
    private Integer sizeBytes;

    /** SHA-256 of the bytes, so a re-upload of identical content is visible as such. */
    @Field("sourceHash")
    private String sourceHash;

    /**
     * True when the bytes are safe to render as text in the panel.
     *
     * Decided at upload from the extension and a scan for NUL bytes, not re-derived on read:
     * the answer cannot change, and getting it wrong mid-contest means a panel full of
     * mojibake at the worst possible moment.
     */
    @Field("textual")
    private Boolean textual;

    @Field("content")
    private byte[] content;

    @Field("uploadedAt")
    private Instant uploadedAt;

    @Field("updatedAt")
    private Instant updatedAt;
}
