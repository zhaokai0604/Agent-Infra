package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.LogTemplateRecord;
import com.award.log.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "Template", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/v1/templates")
public class TemplateManagementController {

    private final TemplateService templateService;

    public TemplateManagementController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @Operation(summary = "分页查询模板列表")
    @GetMapping
    public Result<List<LogTemplateRecord>> list(@RequestParam(defaultValue = "1") int pageNum,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(templateService.page(pageNum, pageSize));
    }

    @Operation(summary = "查询单个模板详情")
    @GetMapping("/{id}")
    public Result<LogTemplateRecord> get(@PathVariable String id) {
        return Result.success(templateService.get(id));
    }

    @Operation(summary = "修改模板名称和严重等级")
    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable String id, @RequestBody LogTemplateRecord record) {
        return Result.success(templateService.update(id, record));
    }

    @Operation(summary = "删除未使用模板")
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable String id) {
        return Result.success(templateService.delete(id));
    }

    @Operation(summary = "合并相似模板")
    @PostMapping("/{id}/merge")
    public Result<Boolean> merge(@PathVariable String id, @RequestBody Map<String, String> body) {
        return Result.success(templateService.merge(id, body.get("targetId")));
    }
}
