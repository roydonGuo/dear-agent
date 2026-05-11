package com.roydon.dear.prompt.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.prompt.entity.AiPromptCategory;

import java.util.List;

public interface AiPromptCategoryService extends IService<AiPromptCategory> {

    List<AiPromptCategory> listAllOrdered();
}
