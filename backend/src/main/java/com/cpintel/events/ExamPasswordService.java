package com.cpintel.events;

import com.cpintel.entity.ExamPasscode;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ExamPasscodeRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generating, reading back and rotating the passwords that open an examination.
 *
 * <p>Two of them, answering different questions:
 *
 * <ul>
 *   <li>The <b>examination password</b> is one string for the whole paper. It is what the
 *       invigilator says out loud when the paper starts, and it means "this sitting has begun,
 *       in this room, now" — which an assignment made a fortnight earlier cannot mean.</li>
 *   <li>A <b>candidate's passcode</b> is theirs alone for that paper, printed on the slip on
 *       their desk. It means "the person typing this is the person this seat belongs to",
 *       which the shared password cannot mean, because by the time the paper starts everyone
 *       in the room has it.</li>
 * </ul>
 *
 * <p>Neither is ever emailed, and there is deliberately no route that would. They are printed
 * and carried into the room, and that is the whole arrangement: the credential in somebody's
 * mailbox gets them into CPIntel, and getting into the paper additionally needs something only
 * the invigilator can hand them. An emailed examination password would collapse the two into
 * one, and a candidate at home with a phone would have everything they needed.
 *
 * <h2>Why these are encrypted rather than hashed</h2>
 *
 * <p>Everywhere else in this system a secret is hashed, because nothing needs to read it back.
 * Here somebody does, twice over: the invigilator prints the desk slips before the paper, and
 * re-reads one code at eleven o'clock for the candidate whose slip is under a radiator. A hash
 * serves neither, and the alternative — regenerating a candidate's code every time a piece of
 * paper goes missing — is the kind of rule that gets abandoned by the second sitting, taking
 * the rest of the arrangement with it.
 *
 * <p>So: AES-256-GCM, under {@code CPINTEL_EXAM_PASSWORD_KEY}, which lives in the environment
 * and not in this database. A dump of Postgres alone yields nothing. What bounds the damage
 * beyond that is the secret itself — an examination password is worthless the moment the
 * paper's window closes, which is a property none of the account passwords in this system
 * have.
 *
 * <p>The reveal route is admin-only and audited every time it is called, so reading the codes
 * is an event with a name against it rather than a silent query.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamPasswordService {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;

    /**
     * The alphabet codes are drawn from.
     *
     * No {@code O}, {@code 0}, {@code I}, {@code 1} or {@code l}: these are read off paper,
     * under pressure, by somebody who has one job that is not reading a code. Ambiguity here
     * costs an invigilator a trip across the room, so it is designed out rather than explained
     * away in a help string.
     */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private final GroupContestRepository events;
    private final ExamPasscodeRepository passcodes;
    private final UserRepository users;
    private final AuditService auditService;

    private final SecureRandom random = new SecureRandom();

    @Value("${cpintel.exams.password-key:}")
    private String passwordKey;

    /** Characters per group, and groups per code: {@code K7FQ-M2XB}. */
    @Value("${cpintel.exams.password-length:8}")
    private int passwordLength;

    /** True when an operator has set a key, without which nothing here can be generated. */
    public boolean isConfigured() {
        return passwordKey != null && !passwordKey.isBlank();
    }

    // ------------------------------------------------- the shared password

    /**
     * Generates or regenerates the examination's shared password, returning it once.
     *
     * <p>Regenerating bumps the generation counter, which ends every session opened with the
     * old password. That is the point of being able to rotate at all: a password read out to
     * the wrong room is only fixed by closing the sessions it already opened.
     */
    @Transactional
    public String generateExamPassword(Long adminId, Long examId, HttpServletRequest httpReq) {
        GroupContest exam = requireExam(examId);
        requireKey();

        boolean rotating = exam.getExamPassword() != null;
        String plain = newCode();

        exam.setExamPassword(encrypt(plain));
        exam.setExamPasswordSetAt(Instant.now());
        exam.setExamPasswordSetBy(adminId == null ? null : users.getReferenceById(adminId));
        exam.setExamPasswordGen(exam.getExamPasswordGen() == null
            ? 1 : exam.getExamPasswordGen() + 1);
        events.save(exam);

        auditService.record(adminId, AuditService.EXAM_PASSWORD_SET, "EXAM",
            examId + (rotating ? ":rotated" : ":set"), httpReq);
        log.info("Admin {} {} the examination password for exam {}",
            adminId, rotating ? "rotated" : "set", examId);

        return plain;
    }

    /**
     * Removes the shared password, so the paper opens without one.
     *
     * <p>A deliberate choice an admin can make — a take-home paper, or one where the personal
     * codes are the whole of the check. It does not end anybody's open session, because
     * removing a requirement is not the same as distrusting the people who already met it.
     */
    @Transactional
    public void clearExamPassword(Long adminId, Long examId, HttpServletRequest httpReq) {
        GroupContest exam = requireExam(examId);
        exam.setExamPassword(null);
        exam.setExamPasswordSetAt(null);
        exam.setExamPasswordSetBy(null);
        events.save(exam);

        auditService.record(adminId, AuditService.EXAM_PASSWORD_CLEARED, "EXAM",
            String.valueOf(examId), httpReq);
        log.info("Admin {} cleared the examination password for exam {}", adminId, examId);
    }

    /** The shared password in the clear, for the admin who is about to read it out. */
    @Transactional(readOnly = true)
    public String revealExamPassword(Long examId) {
        GroupContest exam = requireExam(examId);
        return exam.getExamPassword() == null ? null : decrypt(exam.getExamPassword());
    }

    // ----------------------------------------------- the candidates' codes

    /**
     * Issues a code to every assigned candidate who has not got one.
     *
     * <p>Additive by default, which is the behaviour a paper actually needs: a candidate added
     * to the roster an hour before the sitting gets a code without invalidating the two hundred
     * slips already printed. {@code regenerateAll} is the other case — the roster leaked, and
     * every slip is being reprinted anyway.
     */
    @Transactional
    public List<Issued> issuePasscodes(Long adminId, Long examId, Set<Long> participantIds,
                                       boolean regenerateAll, HttpServletRequest httpReq) {
        GroupContest exam = requireExam(examId);
        requireKey();

        if (participantIds.isEmpty()) {
            throw ApiException.badRequest(
                "Nobody is assigned to this examination yet, so there is nobody to issue a "
                + "code to. Assign the teams or candidates first.");
        }

        Map<Long, ExamPasscode> existing = new HashMap<>();
        for (ExamPasscode row : passcodes.findAllForContest(examId)) {
            existing.put(row.getUser().getUserId(), row);
        }

        List<Issued> issued = new ArrayList<>();
        int created = 0;
        int rotated = 0;

        for (Long userId : participantIds) {
            ExamPasscode row = existing.get(userId);
            if (row != null && !regenerateAll) {
                issued.add(toIssued(row, decrypt(row.getCode())));
                continue;
            }

            String plain = newCode();
            if (row == null) {
                User user = users.findById(userId).orElse(null);
                if (user == null) continue;
                row = ExamPasscode.builder()
                    .contest(exam)
                    .user(user)
                    .code(encrypt(plain))
                    .issuedAt(Instant.now())
                    .issuedBy(adminId == null ? null : users.getReferenceById(adminId))
                    .useCount(0)
                    .build();
                created++;
            } else {
                row.setCode(encrypt(plain));
                row.setIssuedAt(Instant.now());
                row.setIssuedBy(adminId == null ? null : users.getReferenceById(adminId));
                // Reset, because the history belonged to a code that no longer opens anything.
                row.setFirstUsedAt(null);
                row.setUseCount(0);
                rotated++;
            }
            issued.add(toIssued(passcodes.save(row), plain));
        }

        // A candidate who was un-assigned keeps a row that opens nothing, which is confusing
        // rather than dangerous — the access check reads the assignment as well. Left alone
        // deliberately: deleting it would lose the record that they were issued one.
        auditService.record(adminId, AuditService.EXAM_PASSCODES_ISSUED, "EXAM",
            examId + ":created=" + created + ",rotated=" + rotated, httpReq);
        log.info("Admin {} issued exam passcodes for exam {}: {} created, {} rotated",
            adminId, examId, created, rotated);

        issued.sort((a, b) -> a.username().compareToIgnoreCase(b.username()));
        return issued;
    }

    /** Regenerates one candidate's code, for the slip that went missing mid-paper. */
    @Transactional
    public Issued reissue(Long adminId, Long examId, Long userId, HttpServletRequest httpReq) {
        GroupContest exam = requireExam(examId);
        requireKey();

        User user = users.findById(userId)
            .orElseThrow(() -> ApiException.notFound("No such candidate"));

        String plain = newCode();
        ExamPasscode row = passcodes.find(examId, userId).orElseGet(() ->
            ExamPasscode.builder().contest(exam).user(user).useCount(0).build());

        row.setCode(encrypt(plain));
        row.setIssuedAt(Instant.now());
        row.setIssuedBy(adminId == null ? null : users.getReferenceById(adminId));
        row.setFirstUsedAt(null);
        row.setUseCount(0);

        auditService.record(adminId, AuditService.EXAM_PASSCODES_ISSUED, "EXAM",
            examId + ":reissued:" + userId, httpReq);
        log.info("Admin {} reissued the exam passcode for user {} on exam {}",
            adminId, userId, examId);

        return toIssued(passcodes.save(row), plain);
    }

    /**
     * Every candidate's code in the clear, for printing the slips.
     *
     * <p>Audited on every call. Reading the room's codes is an action somebody took, and it
     * should be as visible in the trail as issuing them was.
     */
    @Transactional(readOnly = true)
    public List<Issued> revealPasscodes(Long adminId, Long examId, HttpServletRequest httpReq) {
        requireExam(examId);
        auditService.record(adminId, AuditService.EXAM_PASSCODES_READ, "EXAM",
            String.valueOf(examId), httpReq);

        return passcodes.findAllForContest(examId).stream()
            .map(row -> toIssued(row, decrypt(row.getCode())))
            .toList();
    }

    @Transactional
    public void revokePasscodes(Long adminId, Long examId, HttpServletRequest httpReq) {
        requireExam(examId);
        int removed = passcodes.deleteAllForContest(examId);
        auditService.record(adminId, AuditService.EXAM_PASSCODES_ISSUED, "EXAM",
            examId + ":revoked=" + removed, httpReq);
        log.info("Admin {} revoked {} exam passcodes on exam {}", adminId, removed, examId);
    }

    // ------------------------------------------------------- what the gate asks

    /** True when this examination asks for its shared password. */
    public boolean requiresExamPassword(GroupContest exam) {
        return exam.getExamPassword() != null;
    }

    /** True when this candidate has a personal code, and so must present it. */
    @Transactional(readOnly = true)
    public boolean requiresPasscode(Long examId, Long userId) {
        return passcodes.find(examId, userId).isPresent();
    }

    /**
     * Checks what a candidate typed against what was issued.
     *
     * <p>Compared in constant time. The window is small and the codes are short-lived, but a
     * comparison that returns early on the first wrong character is a measurable oracle over a
     * nine-character alphabet, and writing it correctly costs nothing.
     */
    @Transactional
    public boolean verify(GroupContest exam, Long userId, String examPassword, String passcode) {
        boolean ok = true;

        if (requiresExamPassword(exam)) {
            ok = constantTimeEquals(decrypt(exam.getExamPassword()), normalise(examPassword));
        }

        ExamPasscode personal = passcodes.find(exam.getContestId(), userId).orElse(null);
        if (personal != null) {
            // Deliberately not short-circuited on `ok`: both comparisons run whatever the first
            // said, so the time taken does not reveal which half was wrong.
            boolean personalOk = constantTimeEquals(decrypt(personal.getCode()),
                normalise(passcode));
            ok = ok & personalOk;
        }

        if (ok && personal != null) {
            personal.setUseCount((personal.getUseCount() == null ? 0 : personal.getUseCount()) + 1);
            if (personal.getFirstUsedAt() == null) personal.setFirstUsedAt(Instant.now());
            passcodes.save(personal);
        }
        return ok;
    }

    /**
     * Whether this is the candidate's examination password for this paper — the code on their
     * slip, which signs them in to examination mode. Constant-time, and counted when it matches.
     */
    @Transactional
    public boolean matchesPasscode(Long examId, Long userId, String typed) {
        ExamPasscode personal = passcodes.find(examId, userId).orElse(null);
        if (personal == null || typed == null) return false;
        boolean ok = constantTimeEquals(decrypt(personal.getCode()), normalise(typed));
        if (ok) {
            personal.setUseCount((personal.getUseCount() == null ? 0 : personal.getUseCount()) + 1);
            if (personal.getFirstUsedAt() == null) personal.setFirstUsedAt(Instant.now());
            passcodes.save(personal);
        }
        return ok;
    }

    /** Whether this is the room's shared password, for a paper that has one. */
    public boolean matchesExamPassword(GroupContest exam, String typed) {
        if (!requiresExamPassword(exam)) return true;
        return constantTimeEquals(decrypt(exam.getExamPassword()), normalise(typed));
    }

    // -------------------------------------------------------------- shapes

    /**
     * One issued code, with enough of the person attached to print a slip.
     *
     * A record with an explicit {@code toString()}, for the reason
     * {@code DomjudgeCredentialStore.Stored} has one: the generated form would carry the code
     * into the first log line this is interpolated into.
     */
    public record Issued(Long userId, String username, String fullName, String code,
                         Instant issuedAt, Instant firstUsedAt, int useCount) {
        @Override
        public String toString() {
            return "ExamPasscode[user=" + username + ", used=" + useCount + "]";
        }
    }

    private Issued toIssued(ExamPasscode row, String plain) {
        User user = row.getUser();
        return new Issued(user.getUserId(), user.getUsername(), user.getFullName(), plain,
            row.getIssuedAt(), row.getFirstUsedAt(),
            row.getUseCount() == null ? 0 : row.getUseCount());
    }

    // ------------------------------------------------------------- helpers

    private GroupContest requireExam(Long examId) {
        GroupContest event = events.findById(examId)
            .orElseThrow(() -> ApiException.notFound("No such examination"));
        if (!event.isExam()) {
            throw ApiException.badRequest(
                "Only an examination has passwords. A contest is open to whoever it is "
                + "assigned to, for as long as its window is open.");
        }
        return event;
    }

    private void requireKey() {
        if (!isConfigured()) {
            throw ApiException.badRequest(
                "CPINTEL_EXAM_PASSWORD_KEY is not set, so examination passwords cannot be "
                + "generated. Set it and restart before running a password-protected paper.");
        }
    }

    /** {@code K7FQ-M2XB} — grouped in fours, because that is how it gets read off a slip. */
    private String newCode() {
        int length = Math.max(6, Math.min(16, passwordLength));
        StringBuilder out = new StringBuilder(length + length / 4);
        for (int i = 0; i < length; i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            out.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return out.toString();
    }

    /**
     * What somebody typed, in the form the code was generated in.
     *
     * Upper-cased, and the separators dropped and reinserted — so a candidate who types the
     * hyphens, who does not, or who leaves a space where one was printed, all get in. None of
     * those is a different answer, and refusing them would send people to the invigilator over
     * punctuation.
     */
    private String normalise(String typed) {
        if (typed == null) return "";
        String bare = typed.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
        StringBuilder out = new StringBuilder(bare.length() + bare.length() / 4);
        for (int i = 0; i < bare.length(); i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            out.append(bare.charAt(i));
        }
        return out.toString();
    }

    /**
     * Compares in constant time, and never accepts an empty expectation.
     *
     * <p>The empty case is the one that matters. {@link #decrypt} answers an unreadable blob
     * with an empty string — which is right, because an admin screen must not fall over
     * because somebody rotated the key — and without this guard a rotated key would turn every
     * examination password in the deployment into "type nothing". Refusing is the correct
     * outcome: the admin regenerates, which is the only recovery there is anyway.
     */
    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || expected.isEmpty()) return false;
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- crypto

    private SecretKey key() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(passwordKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive the examination password key", e);
        }
    }

    private String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // No detail from the cause: it could otherwise echo what was being encrypted.
            throw new IllegalStateException("Could not store an examination password");
        }
    }

    /**
     * Reads a stored code back.
     *
     * <p>A rotated key invalidates every stored code at once, and this answers that with an
     * empty string rather than an exception: an unreadable code must not open a paper, and
     * must not take the admin screen down either. The admin regenerates, which is the only
     * recovery there is.
     */
    private String decrypt(String blob) {
        if (blob == null) return "";
        try {
            byte[] raw = Base64.getDecoder().decode(blob);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH),
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not read a stored examination password ({}). "
                + "Has CPINTEL_EXAM_PASSWORD_KEY changed? Regenerate to recover.",
                e.getClass().getSimpleName());
            return "";
        }
    }
}
