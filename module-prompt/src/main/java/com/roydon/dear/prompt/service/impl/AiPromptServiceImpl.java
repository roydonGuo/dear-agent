package com.roydon.dear.prompt.service.impl;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.prompt.entity.AiPrompt;
import com.roydon.dear.prompt.mapper.AiPromptMapper;
import com.roydon.dear.prompt.service.AiPromptService;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.List;

@Service
public class AiPromptServiceImpl extends ServiceImpl<AiPromptMapper, AiPrompt> implements AiPromptService {

    @Override
    public IPage<AiPrompt> pageWithCategoryIds(IPage<AiPrompt> page, List<Long> categoryIds) {
        LambdaQueryWrapper<AiPrompt> wrapper = new LambdaQueryWrapper<>();
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
        wrapper.orderByDesc(AiPrompt::getCreateTime);
        return this.page(page, wrapper);
    }

    @Override
    @Cached(name = ":prompt:cache:", key = "#id", cacheType = CacheType.BOTH, cacheNullValue = true)
    public AiPrompt getById(Serializable id) {
        return super.getById(id);
    }

    @Override
    @CacheInvalidate(name = ":prompt:cache:", key = "#id")
    public void evictById(Long id) {
        // 仅触发缓存失效
    }
}
