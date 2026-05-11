package com.roydon.dear.prompt.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.prompt.entity.AiPrompt;

import java.util.List;

public interface AiPromptService extends IService<AiPrompt> {

    IPage<AiPrompt> pageWithCategoryIds(IPage<AiPrompt> page, List<Long> categoryIds);
}
