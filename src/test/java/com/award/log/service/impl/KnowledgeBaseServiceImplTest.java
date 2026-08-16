package com.award.log.service.impl;

import com.award.log.config.KnowledgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplTest {

    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setEnabled(true);
        properties.setVectorDimensions(1024);
        service = new KnowledgeBaseServiceImpl(properties);
        ReflectionTestUtils.setField(service, "qdrantUrl", "http://127.0.0.1:6333");
        ReflectionTestUtils.setField(service, "qdrantApiKey", "");
        ReflectionTestUtils.setField(service, "collectionName", "ops_knowledge");
    }

    @Test
    void deleteShouldRejectBlankPointId() {
        assertFalse(service.delete(" "));
    }

    @Test
    void deleteDocumentShouldRejectBlankDocumentId() {
        assertFalse(service.deleteDocument(null));
    }

    @Test
    void getStatusShouldExposeConfiguration() {
        Map<String, Object> status = service.getStatus();
        assertEquals(Boolean.TRUE, status.get("enabled"));
        assertEquals("ops_knowledge", status.get("collection"));
        assertEquals("http://127.0.0.1:6333", status.get("qdrantUrl"));
        assertTrue(status.containsKey("qdrantConnected"));
    }

    @Test
    void uploadShouldRejectBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> service.upload("title", " "));
    }
}
