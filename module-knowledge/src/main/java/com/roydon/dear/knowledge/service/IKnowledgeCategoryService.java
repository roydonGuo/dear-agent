package com.roydon.dear.knowledge.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.knowledge.domain.entity.KnowledgeCategoryDO;
import com.roydon.dear.knowledge.domain.resp.KnowledgeCategoryResp;

import java.util.List;

public interface IKnowledgeCategoryService extends IService<KnowledgeCategoryDO> {

    List<KnowledgeCategoryDO> listAllOrdered();

    KnowledgeCategoryResp findById(Long id);

    List<KnowledgeCategoryResp> listAll();

    void evictListCache();

    void evictById(Long id);
}
