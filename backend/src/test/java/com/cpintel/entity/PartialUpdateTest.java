package com.cpintel.entity;

import org.hibernate.annotations.DynamicUpdate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These rows are changed from two sides at once: a person's own request and an admin's, or a
 * scheduler's and an admin's. A whole-row UPDATE from an entity loaded before the other side
 * committed wrote the old values back over it (a deactivation, a rotated exam code, mark-done).
 * With dynamic updates a save writes only the columns that request changed.
 */
class PartialUpdateTest {

    @ParameterizedTest(name = "{0} writes only changed columns")
    @ValueSource(classes = {User.class, GroupContest.class, ExamPasscode.class, Classroom.class})
    void writesOnlyChangedColumns(Class<?> entity) {
        assertTrue(entity.isAnnotationPresent(DynamicUpdate.class));
    }
}
