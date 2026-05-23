package com.roydon.dear.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roydon.dear.common.BaseResult;
import io.micrometer.core.annotation.Timed;
import com.roydon.dear.core.service.FileStorage;
import com.roydon.dear.core.service.FileUploadResult;
import com.roydon.dear.prompt.entity.AiPrompt;
import com.roydon.dear.prompt.entity.AiPromptCategory;
import com.roydon.dear.prompt.req.AiPromptCategoryRequest;
import com.roydon.dear.prompt.req.AiPromptRequest;
import com.roydon.dear.prompt.resp.PageResult;
import com.roydon.dear.prompt.service.AiPromptCategoryService;
import com.roydon.dear.prompt.service.AiPromptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "人设管理")
@RestController
@RequestMapping("/prompt")
@RequiredArgsConstructor
public class PromptController {

    private final AiPromptCategoryService categoryService;
    private final AiPromptService promptService;
    private final FileStorage fileStorage;

    // ====== 人设分类 ======

    @Timed(value = "prompt.category.list", description = "List prompt categories")
    @GetMapping("/category/list")
    @Operation(summary = "人设分类列表（集合，无分页）")
    public BaseResult<List<AiPromptCategory>> listCategories() {
        return BaseResult.newSuccess(categoryService.listAllOrdered());
    }

    @Timed(value = "prompt.category.create", description = "Create prompt category")
    @PostMapping("/category")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "新增人设分类")
    public BaseResult<AiPromptCategory> createCategory(@RequestBody AiPromptCategoryRequest request) {
        AiPromptCategory category = new AiPromptCategory();
        category.setName(request.getName());
        category.setSort(request.getSort());
        category.setIcon(request.getIcon());
        categoryService.save(category);
        return BaseResult.newSuccess(category);
    }

    @Timed(value = "prompt.category.detail", description = "Get prompt category detail")
    @GetMapping("/category/{id}")
    @Operation(summary = "查询人设分类详情")
    public BaseResult<AiPromptCategory> getCategory(@PathVariable Long id) {
        return BaseResult.newSuccess(categoryService.getById(id));
    }

    @Timed(value = "prompt.category.update", description = "Update prompt category")
    @PutMapping("/category/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "编辑人设分类")
    public BaseResult<AiPromptCategory> updateCategory(@PathVariable Long id, @RequestBody AiPromptCategoryRequest request) {
        AiPromptCategory category = categoryService.getById(id);
        if (category == null) {
            return BaseResult.newError("分类不存在");
        }
        category.setName(request.getName());
        category.setSort(request.getSort());
        category.setIcon(request.getIcon());
        categoryService.updateById(category);
        return BaseResult.newSuccess(category);
    }

    @Timed(value = "prompt.category.delete", description = "Delete prompt category")
    @DeleteMapping("/category/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除人设分类")
    public BaseResult<Void> deleteCategory(@PathVariable Long id) {
        AiPromptCategory category = categoryService.getById(id);
        if (category == null) {
            return BaseResult.newError("分类不存在");
        }
        categoryService.removeById(id);
        return BaseResult.newSuccess();
    }

    // ====== 人设 ======

    @Timed(value = "prompt.list", description = "List prompts")
    @GetMapping("/list")
    @Operation(summary = "人设分页列表")
    public BaseResult<PageResult<AiPrompt>> listPrompts(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String categoryIds) {
        Page<AiPrompt> page = new Page<>(pageNum, pageSize);
        List<Long> ids = null;
        if (StringUtils.isNotBlank(categoryIds)) {
            ids = Arrays.stream(categoryIds.split(","))
                    .map(s -> s.trim())
                    .filter(StringUtils::isNotBlank)
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
        }
        IPage<AiPrompt> resultPage = promptService.pageWithCategoryIds(page, ids);
        resultPage.getRecords().forEach(this::resolveAvatarUrl);
        PageResult<AiPrompt> pageResult = PageResult.<AiPrompt>builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .total(resultPage.getTotal())
                .records(resultPage.getRecords())
                .build();
        return BaseResult.newSuccess(pageResult);
    }

    @Timed(value = "prompt.create", description = "Create prompt")
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "新增人设")
    public BaseResult<AiPrompt> createPrompt(@RequestBody AiPromptRequest request) {
        AiPrompt prompt = new AiPrompt();
        prompt.setTitle(request.getTitle());
        setAvatarFields(prompt, request.getAvatar());
        prompt.setDescription(request.getDescription());
        prompt.setPrompt(request.getPrompt());
        prompt.setCategoryIds(request.getCategoryIds());
        promptService.save(prompt);
        promptService.evictById(prompt.getId());
        return BaseResult.newSuccess(prompt);
    }

    @Timed(value = "prompt.detail", description = "Get prompt detail")
    @GetMapping("/{id}")
    @Operation(summary = "查询人设详情")
    public BaseResult<AiPrompt> getPrompt(@PathVariable Long id) {
        AiPrompt prompt = promptService.getById(id);
        if (prompt != null) {
            resolveAvatarUrl(prompt);
        }
        return BaseResult.newSuccess(prompt);
    }

    @Timed(value = "prompt.update", description = "Update prompt")
    @PutMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "编辑人设")
    public BaseResult<AiPrompt> updatePrompt(@PathVariable Long id, @RequestBody AiPromptRequest request) {
        AiPrompt prompt = promptService.getById(id);
        if (prompt == null) {
            return BaseResult.newError("人设不存在");
        }
        prompt.setTitle(request.getTitle());
        setAvatarFields(prompt, request.getAvatar());
        prompt.setDescription(request.getDescription());
        prompt.setPrompt(request.getPrompt());
        prompt.setCategoryIds(request.getCategoryIds());
        promptService.updateById(prompt);
        promptService.evictById(id);
        return BaseResult.newSuccess(prompt);
    }

    /**
     * 如果 avatar 是 base64 格式则上传到 MinIO，同时设置 avatar URL 和 avatarKey；
     * 否则只设置 avatar（保留已有的 avatarKey 不变）
     */
    private void setAvatarFields(AiPrompt prompt, String avatar) {
        if (StringUtils.isNotBlank(avatar) && avatar.startsWith("data:")) {
            FileUploadResult result = fileStorage.uploadBase64WithResult(avatar);
            prompt.setAvatar(result.getUrl());
            prompt.setAvatarKey(result.getKey());
        } else {
            prompt.setAvatar(avatar);
        }
    }

    /**
     * 根据 avatarKey 重新生成 presigned URL，覆盖 avatar 字段
     */
    private void resolveAvatarUrl(AiPrompt prompt) {
        if (StringUtils.isNotBlank(prompt.getAvatarKey())) {
            prompt.setAvatar(fileStorage.getFileUrl(prompt.getAvatarKey()));
        }
    }

    @Timed(value = "prompt.delete", description = "Delete prompt")
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除人设")
    public BaseResult<Void> deletePrompt(@PathVariable Long id) {
        AiPrompt prompt = promptService.getById(id);
        if (prompt == null) {
            return BaseResult.newError("人设不存在");
        }
        promptService.removeById(id);
        promptService.evictById(id);
        return BaseResult.newSuccess();
    }
}
