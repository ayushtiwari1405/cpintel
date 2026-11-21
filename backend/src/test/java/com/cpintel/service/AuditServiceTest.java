package com.cpintel.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditServiceTest {

    @Test
    @DisplayName("no audit method opens a transaction: that would take a second pooled connection "
        + "while the caller holds one, and deadlocks the pool when a room unlocks at once")
    void auditWritesNeverTakeARequestPoolConnection() {
        assertThat(AuditService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(Arrays.stream(AuditService.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Transactional.class))
            .map(Method::getName)).isEmpty();
    }

    @Test
    @DisplayName("an entry is written with its fields, clipped to the column sizes")
    void writesClipped() {
        AuditWriter writer = mock(AuditWriter.class);
        new AuditService(writer).recordIn(7L, 9L, "EXAM_UNLOCKED", "EXAM", "x".repeat(300), null);
        verify(writer).insert(eq(9L), eq(7L), eq("EXAM_UNLOCKED"), eq("EXAM"),
            eq("x".repeat(100)), eq(null), eq(null));
    }

    @Test
    @DisplayName("a failing audit write is logged, not thrown into the request that caused it")
    void aFailedWriteDoesNotBreakTheCaller() {
        AuditWriter writer = mock(AuditWriter.class);
        doThrow(new IllegalStateException("pool timeout")).when(writer)
            .insert(any(), any(), any(), any(), any(), any(), any());
        new AuditService(writer).record(1L, "LOGIN", "USER", "1");
    }
}
