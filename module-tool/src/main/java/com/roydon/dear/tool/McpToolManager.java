package com.roydon.dear.tool;

import com.roydon.dear.tool.registry.McpRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class McpToolManager {

    @Autowired
    private McpRegistry registry;

    @Autowired
    private FileOperationTools fileOperationTools;

//    @Autowired
//    private WeatherService weatherService;

    private ToolCallback[] fileToolCallbacks;
//    private ToolCallback[] weatherToolCallbacks;

    @PostConstruct
    public void init() {
        MethodToolCallbackProvider fileProvider = MethodToolCallbackProvider.builder()
                .toolObjects(fileOperationTools).build();
        fileToolCallbacks = fileProvider.getToolCallbacks();

//        MethodToolCallbackProvider weatherProvider = MethodToolCallbackProvider.builder()
//                .toolObjects(weatherService).build();
//        weatherToolCallbacks = weatherProvider.getToolCallbacks();

        log.info("内置工具初始化完成，文件操作: {}", fileToolCallbacks.length);
    }

    public ToolCallback[] getAllTools() {
        List<ToolCallback> all = new ArrayList<>();
        all.addAll(registry.getAllToolCallbacks());
        all.addAll(Arrays.asList(fileToolCallbacks));
//        all.addAll(Arrays.asList(weatherToolCallbacks));
        return all.toArray(ToolCallback[]::new);
    }

    public ToolCallback[] getFileTools() {
        return fileToolCallbacks;
    }
}
