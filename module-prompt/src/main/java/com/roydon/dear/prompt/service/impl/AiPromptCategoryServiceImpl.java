package com.roydon.dear.prompt.service.impl;

import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.prompt.entity.AiPromptCategory;
import com.roydon.dear.prompt.mapper.AiPromptCategoryMapper;
import com.roydon.dear.prompt.service.AiPromptCategoryService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AiPromptCategoryServiceImpl extends ServiceImpl<AiPromptCategoryMapper, AiPromptCategory> implements AiPromptCategoryService {

    @Override
    @Cached(name = ":aiPromptCategory:list", expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES, cacheType = CacheType.BOTH, cacheNullValue = true)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public List<AiPromptCategory> listAllOrdered() {
        LambdaQueryWrapper<AiPromptCategory> wrapper = new LambdaQueryWrapper<AiPromptCategory>()
                .orderByAsc(AiPromptCategory::getSort);
        return this.list(wrapper);
    }
}
