package com.roydon.dear.session.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.session.entity.McpServerConfig;
import com.roydon.dear.session.mapper.McpServerConfigMapper;
import com.roydon.dear.session.service.McpServerConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class McpServerConfigServiceImpl extends ServiceImpl<McpServerConfigMapper, McpServerConfig> implements McpServerConfigService {

    @Override
    public McpServerConfig getByName(String name) {
        LambdaQueryWrapper<McpServerConfig> wrapper = new LambdaQueryWrapper<McpServerConfig>()
                .eq(McpServerConfig::getName, name);
        return this.getOne(wrapper);
    }

    @Override
    public List<McpServerConfig> listAllOrdered() {
        LambdaQueryWrapper<McpServerConfig> wrapper = new LambdaQueryWrapper<McpServerConfig>()
                .orderByAsc(McpServerConfig::getSortOrder);
        return this.list(wrapper);
    }

    @Override
    public List<McpServerConfig> listEnabledOrdered() {
        LambdaQueryWrapper<McpServerConfig> wrapper = new LambdaQueryWrapper<McpServerConfig>()
                .eq(McpServerConfig::getEnabled, true)
                .orderByAsc(McpServerConfig::getSortOrder);
        return this.list(wrapper);
    }
}
