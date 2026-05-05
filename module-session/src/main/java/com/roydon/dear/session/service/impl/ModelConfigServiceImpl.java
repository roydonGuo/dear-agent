package com.roydon.dear.session.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.session.entity.ModelConfig;
import com.roydon.dear.session.mapper.ModelConfigMapper;
import com.roydon.dear.session.service.ModelConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelConfigServiceImpl extends ServiceImpl<ModelConfigMapper, ModelConfig> implements ModelConfigService {

    @Override
    public ModelConfig getByName(String name) {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getName, name);
        return this.getOne(wrapper);
    }

    @Override
    public List<ModelConfig> listAllOrdered() {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
                .orderByAsc(ModelConfig::getSortOrder);
        return this.list(wrapper);
    }

    @Override
    public List<ModelConfig> listEnabled() {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getEnabled, true)
                .orderByAsc(ModelConfig::getSortOrder);
        return this.list(wrapper);
    }

    @Override
    public List<ModelConfig> listByCategory(String category) {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getCategory, category)
                .orderByAsc(ModelConfig::getSortOrder);
        return this.list(wrapper);
    }
}
