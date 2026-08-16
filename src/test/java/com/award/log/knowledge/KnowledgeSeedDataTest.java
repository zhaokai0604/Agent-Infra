package com.award.log.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSeedDataTest {

    @Test
    void allShouldReturnBuiltInRunbooks() {
        assertEquals(5, KnowledgeSeedData.all().size());
        assertTrue(KnowledgeSeedData.all().stream().allMatch(e -> e.title() != null && !e.content().isBlank()));
    }
}
