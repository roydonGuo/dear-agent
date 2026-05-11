package com.roydon.dear.prompt.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.prompt.entity.AiPromptCategory;
import com.roydon.dear.prompt.mapper.AiPromptCategoryMapper;
import com.roydon.dear.prompt.service.AiPromptCategoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiPromptCategoryServiceImpl extends ServiceImpl<AiPromptCategoryMapper, AiPromptCategory> implements AiPromptCategoryService {

    @Override
    public List<AiPromptCategory> listAllOrdered() {
        LambdaQueryWrapper<AiPromptCategory> wrapper = new LambdaQueryWrapper<AiPromptCategory>()
                .orderByAsc(AiPromptCategory::getSort);
        return this.list(wrapper);
    }
}
