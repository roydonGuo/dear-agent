package com.roydon.dear.knowledge.service.impl;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.knowledge.domain.entity.KnowledgeBaseDO;
import com.roydon.dear.knowledge.domain.entity.convertor.KnowledgeBaseConvertor;
import com.roydon.dear.knowledge.domain.resp.KnowledgeBaseResp;
import com.roydon.dear.knowledge.mapper.KnowledgeBaseMapper;
import com.roydon.dear.knowledge.service.IKnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBaseDO> implements IKnowledgeBaseService {

    public static final String CACHE_NAME = ":knowledge_base:";
    public static final String CACHE_NAME_ID = "id:";
    public static final String CACHE_NAME_LIST = "list:";

    private final KnowledgeBaseConvertor knowledgeBaseConvertor;

    @Override
    public IPage<KnowledgeBaseDO> pageWithCategoryIds(IPage<KnowledgeBaseDO> page, List<Long> categoryIds) {
        LambdaQueryWrapper<KnowledgeBaseDO> wrapper = new LambdaQueryWrapper<>();
        if (categoryIds != null && !categoryIds.isEmpty()) {
            wrapper.and(w -> {
                for (int i = 0; i < categoryIds.size(); i++) {
                    if (i > 0) {
                        w.or();
                    }
                    w.apply("FIND_IN_SET({0}, category_ids) > 0", categoryIds.get(i));
                }
            });
        }
        return this.page(page, wrapper);
    }

    @Override
    public List<KnowledgeBaseDO> listAllOrdered() {
        return this.list();
    }

    // ==================== DTO-returning cached methods ====================

    @Override
    @Cached(name = CACHE_NAME + CACHE_NAME_ID, key = "#id", cacheType = CacheType.BOTH, cacheNullValue = true, expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public KnowledgeBaseResp findById(Long id) {
        KnowledgeBaseDO entity = getById(id);
        return knowledgeBaseConvertor.toResp(entity);
    }

    @Override
    @Cached(name = CACHE_NAME + CACHE_NAME_LIST, cacheType = CacheType.BOTH, cacheNullValue = true, expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public List<KnowledgeBaseResp> listAll() {
        List<KnowledgeBaseDO> entities = listAllOrdered();
        return knowledgeBaseConvertor.toRespList(entities);
    }

    @Override
//    @Cached(name = CACHE_NAME + CACHE_NAME_LIST, key = "#page.current + '_' + #page.size + '_' + (T(java.util.Objects).toString(#categoryIds, 'all'))", cacheType = CacheType.BOTH, cacheNullValue = true, expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
//    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public IPage<KnowledgeBaseResp> pageList(IPage<KnowledgeBaseResp> page, List<Long> categoryIds) {
        var doPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<KnowledgeBaseDO>(
                page.getCurrent(), page.getSize());
        IPage<KnowledgeBaseDO> doResult = pageWithCategoryIds(doPage, categoryIds);
        List<KnowledgeBaseResp> respList = knowledgeBaseConvertor.toRespList(doResult.getRecords());
        page.setRecords(respList);
        page.setTotal(doResult.getTotal());
        return page;
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
        log.debug("evictListCache[:knowledge_base:list:]");
    }
}
