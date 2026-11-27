package com.cpintel.events;

import com.cpintel.groups.GroupsDto;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Items inside a request's list are checked like the request itself: unchecked, they reached the
 * database and came back as a server error.
 */
class EventsDtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> refused(Object request) {
        return validator.validate(request).stream()
            .map(v -> v.getPropertyPath().toString())
            .collect(Collectors.toSet());
    }

    private static EventsDto.ProblemRequest problem(String label, Double points) {
        return new EventsDto.ProblemRequest(label, "Sum", "sum", 0, points);
    }

    @Test
    @DisplayName("a problem's label, title, id and marks are held to their limits")
    void problems() {
        var request = new EventsDto.ProblemsRequest(Arrays.asList(
            problem("A".repeat(17), 1.0),
            new EventsDto.ProblemRequest("B", "t".repeat(201), "i".repeat(101), 0, 1.0),
            problem("C", -50.0),
            problem("D", 1e12),
            null));

        assertEquals(Set.of("problems[0].label", "problems[1].title", "problems[1].externalId",
            "problems[2].points", "problems[3].points", "problems[4].<list element>"),
            refused(request));
    }

    @Test
    @DisplayName("no marks, zero and the maximum are all allowed")
    void allowedMarks() {
        assertEquals(Set.of(), refused(new EventsDto.ProblemsRequest(List.of(
            problem("A", null), problem("B", 0.0), problem("C", 100000.0)))));
    }

    @Test
    @DisplayName("a reported examination event is checked, and named by its place in the batch")
    void examEvents() {
        var fine = new EventsDto.ClientEvent("e1", "FOCUS_LOST", null, null, null, null);
        var request = new EventsDto.EventReport(List.of(fine,
            new EventsDto.ClientEvent(null, "FOCUS_LOST", null, null, null, null),
            new EventsDto.ClientEvent("e".repeat(65), "FOCUS_LOST", null, null, null, null),
            new EventsDto.ClientEvent("e4", "FOCUS_LOST", null, null, "d".repeat(501), null)));

        assertEquals(Set.of("events[1].eventId", "events[2].eventId", "events[3].detail"),
            refused(request));
    }

    @Test
    @DisplayName("so is a reported team contest event")
    void violationEvents() {
        var request = new GroupsDto.ViolationReport(List.of(
            new GroupsDto.ViolationEvent("", "FOCUS_LOST", null, null, null)));

        assertEquals(Set.of("events[0].eventId"), refused(request));
    }
}
