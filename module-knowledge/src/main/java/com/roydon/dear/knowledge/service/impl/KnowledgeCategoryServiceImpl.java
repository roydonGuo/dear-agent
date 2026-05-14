package com.roydon.dear.knowledge.service.impl;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.knowledge.domain.entity.KnowledgeCategoryDO;
import com.roydon.dear.knowledge.domain.entity.convertor.KnowledgeCategoryConvertor;
import com.roydon.dear.knowledge.domain.resp.KnowledgeCategoryResp;
import com.roydon.dear.knowledge.mapper.KnowledgeCategoryMapper;
import com.roydon.dear.knowledge.service.IKnowledgeCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class KnowledgeCategoryServiceImpl extends ServiceImpl<KnowledgeCategoryMapper, KnowledgeCategoryDO> implements IKnowledgeCategoryService {

    public static final String CACHE_NAME = ":knowledge_category:";
    public static final String CACHE_NAME_ID = "id:";
    public static final String CACHE_NAME_LIST = "list";

    private final KnowledgeCategoryConvertor knowledgeCategoryConvertor;

    @Override
    public List<KnowledgeCategoryDO> listAllOrdered() {
        LambdaQueryWrapper<KnowledgeCategoryDO> wrapper = new LambdaQueryWrapper<KnowledgeCategoryDO>()
                .orderByAsc(KnowledgeCategoryDO::getSort);
        return this.list(wrapper);
    }

    // ==================== DTO-returning cached methods ====================

    @Override
    @Cached(name = CACHE_NAME + CACHE_NAME_ID, key = "#id", cacheType = CacheType.BOTH, cacheNullValue = true,
            expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public KnowledgeCategoryResp findById(Long id) {
        KnowledgeCategoryDO entity = getById(id);
        return knowledgeCategoryConvertor.toResp(entity);
    }

    @Override
    @Cached(name = CACHE_NAME + CACHE_NAME_LIST, cacheType = CacheType.BOTH, cacheNullValue = true,
            expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public List<KnowledgeCategoryResp> listAll() {
        List<KnowledgeCategoryDO> entities = listAllOrdered();
        return knowledgeCategoryConvertor.toRespList(entities);
    }

    // ==================== Cache invalidation ====================

    @Override
    @CacheInvalidate(name = CACHE_NAME + CACHE_NAME_ID, key = "#id")
    public void evictById(Long id) {
        // 仅触发缓存失效
    }

    @Override
    @CacheInvalidate(name = CACHE_NAME + CACHE_NAME_LIST)
    public void evictListCache() {
        // 仅触发列表缓存失效
    }
}
