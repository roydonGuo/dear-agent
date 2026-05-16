package com.roydon.dear.knowledge.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileResp;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileTreeNode;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface IKnowledgeFileService extends IService<KnowledgeFileDO> {

    List<KnowledgeFileTreeNode> buildTree(Long baseId);

    KnowledgeFileResp findById(Long id);

    /**
     * 上传文件，存储到 MinIO 并创建数据库记录
     *
     * @param file     上传的文件
     * @param baseId   知识库 ID
     * @param parentId 父节点 ID（可选，0 表示根目录）
     * @return 创建的文件记录
     */
    KnowledgeFileResp uploadFile(MultipartFile file, Long baseId, Long parentId);

    /**
     * 获取文件的访问 URL（MinIO 预签名 URL）
     *
     * @param id 文件 ID
     * @return 预签名访问 URL，如果文件无 storagePath 则返回 null
     */
    String getFileUrl(Long id);

    void deleteCascade(Long id);

    void evictTree(Long baseId);

    void evictById(Long id);
}
