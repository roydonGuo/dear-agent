package com.roydon.dear.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.knowledge.domain.entity.KnowledgeBaseDO;
import com.roydon.dear.knowledge.domain.resp.KnowledgeBaseResp;

import java.util.List;

public interface IKnowledgeBaseService extends IService<KnowledgeBaseDO> {

    IPage<KnowledgeBaseDO> pageWithCategoryIds(IPage<KnowledgeBaseDO> page, List<Long> categoryIds);

    List<KnowledgeBaseDO> listAllOrdered();

    KnowledgeBaseResp findById(Long id);

    List<KnowledgeBaseResp> listAll();

    IPage<KnowledgeBaseResp> pageList(IPage<KnowledgeBaseResp> page, List<Long> categoryIds);

    void evictListCache();

    void evictById(Long id);
}
