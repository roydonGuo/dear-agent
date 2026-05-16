package com.roydon.dear.knowledge.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileResp;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileTreeNode;

import java.util.List;

public interface IKnowledgeFileService extends IService<KnowledgeFileDO> {

    List<KnowledgeFileTreeNode> buildTree(Long baseId);

    KnowledgeFileResp findById(Long id);

    void deleteCascade(Long id);

    void evictTree(Long baseId);

    void evictById(Long id);
}
