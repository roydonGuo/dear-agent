package com.roydon.dear.tool.registry;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.roydon.dear.session.entity.McpServerConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class McpTransportFactory {

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    public McpSyncClient create(McpServerConfig cfg) {
        return switch (cfg.getTransport()) {
            case "http" -> createHttpClient(cfg);
            case "stdio" -> createStdioClient(cfg);
            default -> throw new IllegalArgumentException("不支持的传输类型: " + cfg.getTransport());
        };
    }

    private McpSyncClient createHttpClient(McpServerConfig cfg) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        if (StringUtils.isNotBlank(cfg.getApiKey())) {
            requestBuilder.header("Authorization", "Bearer " + cfg.getApiKey());
        }

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(cfg.getMcpUrl())
                .requestBuilder(requestBuilder)
                .build();

        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(cfg.getTimeoutSec() != null ? cfg.getTimeoutSec() : 300))
                .build();

//        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
//
//        if (StringUtils.isNotBlank(cfg.getApiKey())) {
//            requestBuilder.header("Authorization", "Bearer " + cfg.getApiKey());
//        }
//
//        var transport = HttpClientSseClientTransport.builder(cfg.getMcpUrl())
//                .requestBuilder(requestBuilder)
//                .build();
//
//        return McpClient.sync(transport)
//                .requestTimeout(Duration.ofSeconds(cfg.getTimeoutSec() != null ? cfg.getTimeoutSec() : 300))
//                .build();
    }

    private McpSyncClient createStdioClient(McpServerConfig cfg) {
        List<String> args = new ArrayList<>();
        if (StringUtils.isNotBlank(cfg.getArgs())) {
            JSONArray argsArray = JSON.parseArray(cfg.getArgs());
            args = argsArray.toJavaList(String.class);
        }

        Map<String, String> envMap = new HashMap<>();
        if (StringUtils.isNotBlank(cfg.getEnv())) {
            JSONObject envJson = JSON.parseObject(cfg.getEnv());
            for (String key : envJson.keySet()) {
                envMap.put(key, envJson.getString(key));
            }
        }

        String command = cfg.getCommand();

        // Windows 下 ProcessBuilder 不会自动补全 .cmd/.bat 扩展名
        if (IS_WINDOWS && needsWindowsWrapper(command)) {
            List<String> wrappedArgs = new ArrayList<>();
            wrappedArgs.add("/c");
            wrappedArgs.add(command);
            wrappedArgs.addAll(args);
            args = wrappedArgs;
            command = "cmd";
            log.info("Windows: 使用 cmd /c 包装命令: {}", cfg.getCommand());
        }

        ServerParameters params = ServerParameters.builder(command)
                .args(args)
                .env(envMap)
                .build();

        return McpClient.sync(new StdioClientTransport(params, McpJsonMapper.getDefault()))
                .requestTimeout(Duration.ofSeconds(cfg.getTimeoutSec() != null ? cfg.getTimeoutSec() : 300))
                .build();
    }

    private boolean needsWindowsWrapper(String command) {
        String lower = command.toLowerCase();
        if (lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            return false;
        }
        if (command.contains("/") || command.contains("\\")) {
            return false;
        }
        return true;
    }
}
