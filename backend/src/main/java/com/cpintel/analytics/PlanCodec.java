package com.cpintel.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads and writes the JSON a sheet is stored as. Rows written before {@link Plan} existed hold
 * the same field names as plain maps, so they read back into the same records.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanCodec {

    private final ObjectMapper objectMapper;

    /** A stored sheet's items; an unreadable column reads as no items rather than failing the page. */
    public List<Plan.Item> readItems(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Plan.Item> items = objectMapper.readValue(json, new TypeReference<List<Plan.Item>>() {});
            return items == null ? List.of() : items;
        } catch (Exception e) {
            log.warn("Unreadable recommendation items: {}", e.getMessage());
            return List.of();
        }
    }

    /** A stored sheet's summary, or null for rows written before sheets had one. */
    public Plan.Summary readSummary(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Plan.Summary.class);
        } catch (Exception e) {
            log.warn("Unreadable recommendation summary: {}", e.getMessage());
            return null;
        }
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // Records of strings and numbers; this cannot fail short of a programming error.
            throw new IllegalStateException("Could not serialise a recommendation", e);
        }
    }
}
