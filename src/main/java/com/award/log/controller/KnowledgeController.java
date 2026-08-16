package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Tag(name = "Knowledge")
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Operation(summary = "知识库运行状态")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(knowledgeBaseService.getStatus());
    }

    @Operation(summary = "空库时写入内置 Runbook（刷新可触发）")
    @PostMapping("/seed")
    public Result<Map<String, Object>> seed() {
        return Result.success(knowledgeBaseService.seedBuiltinIfEmpty());
    }

    @Operation(summary = "文档列表（按 documentId 聚合）")
    @GetMapping("/documents")
    public Result<Map<String, Object>> documents(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(knowledgeBaseService.listDocuments(page, pageSize));
    }

    @Operation(summary = "上传知识文档（文本）")
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        if (content == null || content.isBlank()) {
            return Result.error("文档内容不能为空");
        }
        return Result.success(knowledgeBaseService.upload(
                body.getOrDefault("title", "untitled"),
                content,
                body.getOrDefault("category", "general"),
                body.getOrDefault("source", "manual")
        ));
    }

    @Operation(summary = "上传知识文件（txt/md/pdf/log）")
    @PostMapping("/upload/file")
    public Result<List<Map<String, Object>>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "general") String category) {
        return Result.success(knowledgeBaseService.uploadFile(file, title, category));
    }

    @Operation(summary = "语义检索")
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        return Result.success(knowledgeBaseService.search(query, topK));
    }

    @Operation(summary = "删除向量点")
    @DeleteMapping("/point/{id}")
    public Result<Boolean> deletePoint(@PathVariable String id) {
        return Result.success(knowledgeBaseService.delete(id));
    }

    @Operation(summary = "删除整篇文档（含所有分块）")
    @DeleteMapping("/document/{documentId}")
    public Result<Boolean> deleteDocument(@PathVariable String documentId) {
        return Result.success(knowledgeBaseService.deleteDocument(documentId));
    }
}
