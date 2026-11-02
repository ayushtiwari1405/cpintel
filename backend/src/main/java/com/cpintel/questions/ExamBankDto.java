package com.cpintel.questions;

import com.cpintel.entity.BankQuestion;
import com.cpintel.entity.ExamPublication;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** An examination's questions from the bank, and what of them is on the judge. */
public class ExamBankDto {

    private ExamBankDto() {}

    public record State(
        /** True before the start: the choice and the whole contest can still change. */
        boolean editable,
        /** True when the examination has a start and an end, which publishing needs. */
        boolean scheduled,
        String judgeContestId,
        Publication publication,
        List<Chosen> chosen,
        /** The classroom's questions that are not archived, for choosing from. */
        List<Available> available,
        List<com.cpintel.events.EventsDto.Announcement> announcements
    ) {}

    public record Publication(
        ExamPublication.Status status,
        boolean contestCreated,
        Instant judgeStart,
        Instant judgeEnd,
        Instant publishedAt,
        String publishedBy,
        String note
    ) {}

    /** A chosen question: as it is in the bank, and as it is on the judge. */
    public record Chosen(
        Long questionId,
        String title,
        BankQuestion.Status status,
        int version,
        boolean archived,
        /** Null until published. */
        String label,
        String judgeProblemId,
        Integer publishedVersion,
        Instant publishedAt,
        /** The bank has a newer version than the judge. */
        boolean changedSincePublished
    ) {}

    public record Available(Long questionId, String title, BankQuestion.Status status, int version) {}

    /** The whole choice, in order. */
    public record SelectionRequest(@NotNull @Size(max = 26) List<Long> questionIds) {}

    /**
     * Publishing one question again during the examination: an explicit yes, and optionally a
     * notice to every candidate.
     */
    public record RepublishRequest(boolean confirm, @Size(max = 1000) String notice) {}
}
