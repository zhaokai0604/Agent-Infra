package com.award.log.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HistoryFilterTimeTest {

    @Test
    void parsePlainDate() {
        assertEquals(LocalDate.of(2026, 5, 9), HistoryFilterTime.parseDateOnly("2026-05-09"));
    }

    @Test
    void parseIsoPrefix() {
        assertEquals(LocalDate.of(2026, 5, 9), HistoryFilterTime.parseDateOnly("2026-05-09T16:00:00.000Z"));
    }

    @Test
    void parseStartEndBounds() {
        assertEquals(LocalDateTime.of(2026, 5, 9, 0, 0, 0), HistoryFilterTime.parseStart("2026-05-09"));
        assertEquals(LocalDateTime.of(2026, 5, 9, 23, 59, 59), HistoryFilterTime.parseEnd("2026-05-09"));
    }

    @Test
    void blankReturnsNull() {
        assertNull(HistoryFilterTime.parseDateOnly(null));
        assertNull(HistoryFilterTime.parseDateOnly("  "));
        assertNull(HistoryFilterTime.parseDateOnly("invalid"));
    }
}
