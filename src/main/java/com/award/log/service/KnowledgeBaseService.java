package com.award.log.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface KnowledgeBaseService {

    Map<String, Object> upload(String title, String content);

    Map<String, Object> upload(String title, String content, String category, String source);

    List<Map<String, Object>> uploadFile(MultipartFile file, String title, String category);

    List<Map<String, Object>> search(String query, int topK);

    boolean delete(String pointId);

    boolean deleteDocument(String documentId);

    Map<String, Object> getStatus();

    Map<String, Object> listDocuments(int page, int pageSize);

    /** 库为空时写入内置 Runbook；刷新按钮可触发。 */
    Map<String, Object> seedBuiltinIfEmpty();
}
