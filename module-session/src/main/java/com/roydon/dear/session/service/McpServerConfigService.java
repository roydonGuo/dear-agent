package com.roydon.dear.session.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.session.entity.McpServerConfig;

import java.util.List;

public interface McpServerConfigService extends IService<McpServerConfig> {

    McpServerConfig getByName(String name);

    List<McpServerConfig> listAllOrdered();

    List<McpServerConfig> listEnabledOrdered();
}
