package com.roydon.dear.web.controller;

import com.roydon.dear.common.BaseResult;
import com.roydon.dear.common.exception.BusinessException;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.convertor.KnowledgeFileConvertor;
import com.roydon.dear.knowledge.domain.req.KnowledgeFileRequest;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileResp;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileTreeNode;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Validated
@Tag(name = "知识库文件管理", description = "知识库文件树查询、文件/文件夹CRUD")
@RestController
@RequestMapping("/knowledge-file")
@RequiredArgsConstructor
public class KnowledgeFileController {

    private final IKnowledgeFileService knowledgeFileService;
    private final KnowledgeFileConvertor knowledgeFileConvertor;

    @GetMapping("/tree")
    @Operation(summary = "查询文件树", description = "根据知识库ID查询完整的文件树结构")
    public BaseResult<List<KnowledgeFileTreeNode>> tree(@RequestParam Long baseId) {
        log.info("查询文件树: baseId={}", baseId);
        List<KnowledgeFileTreeNode> tree = knowledgeFileService.buildTree(baseId);
        return BaseResult.newSuccess(tree);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询文件详情", description = "根据ID查询文件详情（含内容）")
    public BaseResult<KnowledgeFileResp> getById(@PathVariable Long id) {
        log.info("查询文件详情: id={}", id);
        KnowledgeFileResp resp = knowledgeFileService.findById(id);
        if (resp == null) {
            throw new BusinessException("文件不存在");
        }
        return BaseResult.newSuccess(resp);
    }

    @PostMapping
    @Operation(summary = "创建文件/文件夹", description = "创建新的文件或文件夹节点")
    public BaseResult<KnowledgeFileResp> create(@Valid @RequestBody KnowledgeFileRequest request) {
        log.info("创建文件: request={}", request);
        KnowledgeFileDO entity = knowledgeFileConvertor.toEntity(request);
        knowledgeFileService.save(entity);
        knowledgeFileService.evictTree(entity.getBaseId());
        return BaseResult.newSuccess(knowledgeFileConvertor.toResp(entity));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新文件/文件夹", description = "更新文件或文件夹的名称、内容等信息")
    public BaseResult<KnowledgeFileResp> update(@PathVariable Long id, @Valid @RequestBody KnowledgeFileRequest request) {
        log.info("更新文件: id={}, request={}", id, request);
        KnowledgeFileDO entity = knowledgeFileConvertor.toEntity(request);
        entity.setId(id);
        knowledgeFileService.updateById(entity);
        knowledgeFileService.evictById(id);
        knowledgeFileService.evictTree(request.getBaseId());
        KnowledgeFileResp resp = knowledgeFileService.findById(id);
        return BaseResult.newSuccess(resp);
    }

    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除文件/文件夹", description = "删除指定节点，若为文件夹则级联删除所有子节点")
    public BaseResult<String> delete(@PathVariable Long id) {
        log.info("删除文件: id={}", id);
        KnowledgeFileDO entity = knowledgeFileService.getById(id);
        if (entity == null) {
            throw new BusinessException("文件不存在");
        }
        knowledgeFileService.deleteCascade(id);
        knowledgeFileService.evictById(id);
        knowledgeFileService.evictTree(entity.getBaseId());
        return BaseResult.newSuccess("删除成功");
    }
}
