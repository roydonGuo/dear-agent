package com.roydon.dear.session.service.impl;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.session.entity.ModelConfig;
import com.roydon.dear.session.mapper.ModelConfigMapper;
import com.roydon.dear.session.service.ModelConfigService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
public class ModelConfigServiceImpl extends ServiceImpl<ModelConfigMapper, ModelConfig> implements ModelConfigService {

    @Override
    public ModelConfig getByName(String name) {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getName, name);
        return this.getOne(wrapper);
    }

    @Override
    @Cached(name = ":modelConfig:list:", key = "(#category ?: 'all') + '_' + (#enabled ?: 'all')", cacheType = CacheType.BOTH,  expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public List<ModelConfig> listAllOrdered(String category, Boolean enabled) {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
                .eq(StringUtils.isNotBlank(category), ModelConfig::getCategory, category)
                .eq(Objects.nonNull(enabled), ModelConfig::getEnabled, enabled)
                .orderByAsc(ModelConfig::getSortOrder);
        return this.list(wrapper);
    }

    @Override
    @Cached(name = ":modelConfig:enabled", cacheType = CacheType.BOTH,  expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public List<ModelConfig> listEnabled() {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getEnabled, true)
                .orderByAsc(ModelConfig::getSortOrder);
        return this.list(wrapper);
    }

    @Override
    @Cached(name = ":modelConfig:category:", key = "#category", cacheType = CacheType.BOTH,  expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public List<ModelConfig> listByCategory(String category) {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getCategory, category)
                .orderByAsc(ModelConfig::getSortOrder);
        return this.list(wrapper);
    }

    /**
     * 清除modelConfig的列表缓存。
     * modelConfig:enabled 通过注解立即失效，
     * modelConfig:category: 和 modelConfig:list: 通过 300s TTL 自然过期。
     */
    @Override
    @CacheInvalidate(name = ":modelConfig:enabled")
    @CacheInvalidate(name = ":modelConfig:list:all_all")
    @CacheInvalidate(name = ":modelConfig:category")
    public void evictListCache() {
        // @CacheInvalidate 注解处理 keyless 缓存项
        // 带 key 的缓存项依赖 300s 本地过期时间自动刷新
    }
}
