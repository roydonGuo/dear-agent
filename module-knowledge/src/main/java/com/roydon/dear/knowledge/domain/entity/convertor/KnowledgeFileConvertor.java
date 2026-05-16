package com.roydon.dear.knowledge.domain.entity.convertor;

import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.req.KnowledgeFileRequest;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileResp;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;

import java.util.List;

@Mapper(componentModel = "spring", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface KnowledgeFileConvertor {

    KnowledgeFileResp toResp(KnowledgeFileDO entity);

    List<KnowledgeFileResp> toRespList(List<KnowledgeFileDO> entities);

    KnowledgeFileDO toEntity(KnowledgeFileRequest request);
}
