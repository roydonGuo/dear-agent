package com.roydon.dear.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roydon.dear.common.BaseResult;
import com.roydon.dear.core.service.FileStorage;
import com.roydon.dear.core.service.FileUploadResult;
import com.roydon.dear.knowledge.domain.entity.KnowledgeBaseDO;
import com.roydon.dear.knowledge.domain.entity.KnowledgeCategoryDO;
import com.roydon.dear.knowledge.domain.req.KnowledgeBaseRequest;
import com.roydon.dear.knowledge.domain.req.KnowledgeCategoryRequest;
import com.roydon.dear.knowledge.domain.resp.KnowledgeBaseResp;
import com.roydon.dear.knowledge.domain.resp.KnowledgeCategoryResp;
import com.roydon.dear.knowledge.service.IKnowledgeBaseService;
import com.roydon.dear.knowledge.service.IKnowledgeCategoryService;
import com.roydon.dear.session.resp.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "知识库管理")
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final IKnowledgeBaseService knowledgeBaseService;
    private final IKnowledgeCategoryService knowledgeCategoryService;
    private final FileStorage fileStorage;

    // ==================== 知识库分类 ====================

    @GetMapping("/category/list")
    @Operation(summary = "知识库分类列表（集合，无分页）")
    public BaseResult<List<KnowledgeCategoryResp>> listCategories() {
        return BaseResult.newSuccess(knowledgeCategoryService.listAll());
    }

    @PostMapping("/category")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "新增知识库分类")
    public BaseResult<KnowledgeCategoryResp> createCategory(@RequestBody KnowledgeCategoryRequest request) {
        KnowledgeCategoryDO category = new KnowledgeCategoryDO();
        category.setName(request.getName());
        category.setSort(request.getSort() != null ? request.getSort() : 0);
        category.setIcon(request.getIcon());
        knowledgeCategoryService.save(category);
        knowledgeCategoryService.evictListCache();
        KnowledgeCategoryResp resp = KnowledgeCategoryResp.builder()
                .id(category.getId()).name(category.getName())
                .icon(category.getIcon()).sort(category.getSort())
                .createTime(category.getCreateTime()).updateTime(category.getUpdateTime())
                .build();
        return BaseResult.newSuccess(resp);
    }

    @GetMapping("/category/{id}")
    @Operation(summary = "查询知识库分类详情")
    public BaseResult<KnowledgeCategoryResp> getCategory(@PathVariable Long id) {
        return BaseResult.newSuccess(knowledgeCategoryService.findById(id));
    }

    @PutMapping("/category/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "编辑知识库分类")
    public BaseResult<KnowledgeCategoryResp> updateCategory(@PathVariable Long id,
                                                             @RequestBody KnowledgeCategoryRequest request) {
        KnowledgeCategoryDO category = knowledgeCategoryService.getById(id);
        if (category == null) {
            return BaseResult.newError("分类不存在");
        }
        category.setName(request.getName());
        category.setSort(request.getSort());
        category.setIcon(request.getIcon());
        knowledgeCategoryService.updateById(category);
        knowledgeCategoryService.evictById(id);
        knowledgeCategoryService.evictListCache();
        KnowledgeCategoryResp resp = KnowledgeCategoryResp.builder()
                .id(category.getId()).name(category.getName())
                .icon(category.getIcon()).sort(category.getSort())
                .createTime(category.getCreateTime()).updateTime(category.getUpdateTime())
                .build();
        return BaseResult.newSuccess(resp);
    }

    @DeleteMapping("/category/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除知识库分类")
    public BaseResult<Void> deleteCategory(@PathVariable Long id) {
        KnowledgeCategoryDO category = knowledgeCategoryService.getById(id);
        if (category == null) {
            return BaseResult.newError("分类不存在");
        }
        knowledgeCategoryService.removeById(id);
        knowledgeCategoryService.evictById(id);
        knowledgeCategoryService.evictListCache();
        return BaseResult.newSuccess();
    }

    // ==================== 知识库 ====================

    @GetMapping("/list")
    @Operation(summary = "知识库分页列表")
    public BaseResult<PageResult<KnowledgeBaseResp>> listKnowledgeBases(
            @Parameter(description = "页码，默认1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "页大小，默认10") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "分类ID，逗号分隔") @RequestParam(required = false) String categoryIds) {
        List<Long> ids = null;
        if (StringUtils.isNotBlank(categoryIds)) {
            ids = Arrays.stream(categoryIds.split(","))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
        }
        Page<KnowledgeBaseResp> page = new Page<>(pageNum, pageSize);
        IPage<KnowledgeBaseResp> resultPage = knowledgeBaseService.pageList(page, ids);
        resultPage.getRecords().forEach(this::resolveCoverUrl);
        PageResult<KnowledgeBaseResp> pageResult = PageResult.<KnowledgeBaseResp>builder()
                .pageNum(pageNum).pageSize(pageSize)
                .total(resultPage.getTotal()).records(resultPage.getRecords())
                .build();
        return BaseResult.newSuccess(pageResult);
    }

    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "新增知识库")
    public BaseResult<KnowledgeBaseResp> createKnowledgeBase(@RequestBody KnowledgeBaseRequest request) {
        KnowledgeBaseDO knowledgeBase = new KnowledgeBaseDO();
        knowledgeBase.setName(request.getName());
        knowledgeBase.setDescription(request.getDescription());
        setCoverField(knowledgeBase, request.getCoverPath());
        knowledgeBase.setCategoryIds(request.getCategoryIds());
        knowledgeBase.setCreateTime(LocalDateTime.now());
        knowledgeBaseService.save(knowledgeBase);
        knowledgeBaseService.evictListCache();
        return BaseResult.newSuccess(buildBaseResp(knowledgeBase));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询知识库详情")
    public BaseResult<KnowledgeBaseResp> getKnowledgeBase(@PathVariable Long id) {
        KnowledgeBaseResp resp = knowledgeBaseService.findById(id);
        if (resp != null) {
            resolveCoverUrl(resp);
        }
        return BaseResult.newSuccess(resp);
    }

    @PutMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "编辑知识库")
    public BaseResult<KnowledgeBaseResp> updateKnowledgeBase(@PathVariable Long id,
                                                              @RequestBody KnowledgeBaseRequest request) {
        KnowledgeBaseDO knowledgeBase = knowledgeBaseService.getById(id);
        if (knowledgeBase == null) {
            return BaseResult.newError("知识库不存在");
        }
        knowledgeBase.setName(request.getName());
        knowledgeBase.setDescription(request.getDescription());
        setCoverField(knowledgeBase, request.getCoverPath());
        knowledgeBase.setCategoryIds(request.getCategoryIds());
        knowledgeBase.setUpdateTime(LocalDateTime.now());
        knowledgeBaseService.updateById(knowledgeBase);
        knowledgeBaseService.evictById(id);
        knowledgeBaseService.evictListCache();
        return BaseResult.newSuccess(buildBaseResp(knowledgeBase));
    }

    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除知识库")
    public BaseResult<Void> deleteKnowledgeBase(@PathVariable Long id) {
        KnowledgeBaseDO knowledgeBase = knowledgeBaseService.getById(id);
        if (knowledgeBase == null) {
            return BaseResult.newError("知识库不存在");
        }
        knowledgeBaseService.removeById(id);
        knowledgeBaseService.evictById(id);
        knowledgeBaseService.evictListCache();
        return BaseResult.newSuccess();
    }

    // ==================== private helpers ====================

    private KnowledgeBaseResp buildBaseResp(KnowledgeBaseDO entity) {
        return KnowledgeBaseResp.builder()
                .id(entity.getId()).name(entity.getName())
                .description(entity.getDescription()).coverPath(entity.getCoverPath())
                .categoryIds(entity.getCategoryIds())
                .createTime(entity.getCreateTime()).updateTime(entity.getUpdateTime())
                .build();
    }

    private void setCoverField(KnowledgeBaseDO knowledgeBase, String coverPath) {
        if (StringUtils.isNotBlank(coverPath) && coverPath.startsWith("data:")) {
            FileUploadResult result = fileStorage.uploadBase64WithResult(coverPath);
            knowledgeBase.setCoverPath(result.getKey());
        } else {
            knowledgeBase.setCoverPath(coverPath);
        }
    }

    private void resolveCoverUrl(KnowledgeBaseResp resp) {
        if (StringUtils.isNotBlank(resp.getCoverPath())) {
            resp.setCoverPath(fileStorage.getFileUrl(resp.getCoverPath()));
        }
    }
}
