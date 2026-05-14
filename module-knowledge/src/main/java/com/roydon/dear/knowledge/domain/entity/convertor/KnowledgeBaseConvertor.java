package com.roydon.dear.knowledge.domain.entity.convertor;

import com.roydon.dear.knowledge.domain.entity.KnowledgeBaseDO;
import com.roydon.dear.knowledge.domain.req.KnowledgeBaseRequest;
import com.roydon.dear.knowledge.domain.resp.KnowledgeBaseResp;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;

import java.util.List;

@Mapper(componentModel = "spring", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface KnowledgeBaseConvertor {

    KnowledgeBaseResp toResp(KnowledgeBaseDO entity);

    List<KnowledgeBaseResp> toRespList(List<KnowledgeBaseDO> entities);

    KnowledgeBaseDO toEntity(KnowledgeBaseRequest request);
}
