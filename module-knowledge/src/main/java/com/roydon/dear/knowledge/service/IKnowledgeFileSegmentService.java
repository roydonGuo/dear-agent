package com.roydon.dear.knowledge.service;

import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * knowledge-文件片段表(KnowledgeFileSegment)表服务接口
 *
 * @author roydon
 * @since 2026-05-23 18:35:33
 */
public interface IKnowledgeFileSegmentService extends IService<KnowledgeFileSegmentDO>{

    /**
     * 通过ID查询单条数据
     *
     * @param id 主键
     * @return 实例对象
     */
    KnowledgeFileSegmentDO queryById(Long id);

    /**
     * 分页查询
     *
     * @param knowledgeFileSegmentDO 筛选条件
     * @param pageRequest      分页对象
     * @return 查询结果
     */
    Page<KnowledgeFileSegmentDO> queryByPage(KnowledgeFileSegmentDO knowledgeFileSegmentDO, PageRequest pageRequest);

    /**
     * 新增数据
     *
     * @param knowledgeFileSegmentDO 实例对象
     * @return 实例对象
     */
    KnowledgeFileSegmentDO insert(KnowledgeFileSegmentDO knowledgeFileSegmentDO);

    /**
     * 修改数据
     *
     * @param knowledgeFileSegmentDO 实例对象
     * @return 实例对象
     */
    KnowledgeFileSegmentDO update(KnowledgeFileSegmentDO knowledgeFileSegmentDO);

    /**
     * 通过主键删除数据
     *
     * @param id 主键
     * @return 是否成功
     */
    boolean deleteById(Long id);

}
