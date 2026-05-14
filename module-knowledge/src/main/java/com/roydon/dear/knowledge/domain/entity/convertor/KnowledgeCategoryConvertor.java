package com.roydon.dear.knowledge.domain.entity.convertor;

import com.roydon.dear.knowledge.domain.entity.KnowledgeCategoryDO;
import com.roydon.dear.knowledge.domain.req.KnowledgeCategoryRequest;
import com.roydon.dear.knowledge.domain.resp.KnowledgeCategoryResp;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;

import java.util.List;

@Mapper(componentModel = "spring", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface KnowledgeCategoryConvertor {

    KnowledgeCategoryResp toResp(KnowledgeCategoryDO entity);

    List<KnowledgeCategoryResp> toRespList(List<KnowledgeCategoryDO> entities);

    KnowledgeCategoryDO toEntity(KnowledgeCategoryRequest request);
}
