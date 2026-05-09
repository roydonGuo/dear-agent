package com.roydon.dear.session.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.session.entity.ModelConfig;

import java.util.List;

public interface ModelConfigService extends IService<ModelConfig> {

    ModelConfig getByName(String name);

    List<ModelConfig> listAllOrdered(String category,Boolean enabled);

    List<ModelConfig> listEnabled();

    List<ModelConfig> listByCategory(String category);
}
