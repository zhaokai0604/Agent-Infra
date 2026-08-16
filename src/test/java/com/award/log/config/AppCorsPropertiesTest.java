package com.award.log.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppCorsPropertiesTest {

    @Test
    void allowsLocalhostOriginsOnlyByDefault() {
        AppCorsProperties props = new AppCorsProperties();
        assertTrue(props.isOriginAllowed("http://localhost:3000"));
        assertTrue(props.isOriginAllowed("http://127.0.0.1:8088"));
        assertFalse(props.isOriginAllowed("https://evil.example.com"));
    }

    @Test
    void rejectsBlankOrigin() {
        AppCorsProperties props = new AppCorsProperties();
        assertFalse(props.isOriginAllowed(""));
        assertFalse(props.isOriginAllowed(null));
    }
}
