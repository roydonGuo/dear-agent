package com.roydon.dear.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FileOperationTools {

    @Tool(name = "bash", description = "执行 shell 命令 / Execute shell command")
    public String bash(
            @ToolParam(description = "要执行的 shell 命令") String command,
            @ToolParam(description = "超时时间（秒），默认 60") Long timeoutSeconds) {
        log.info("EXECUTE Tool: bash: {}", command);
        long timeout = timeoutSeconds != null && timeoutSeconds > 0 ? timeoutSeconds : 60;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                processBuilder.command("cmd.exe", "/c", command);
            } else {
                processBuilder.command("sh", "-c", command);
            }
            processBuilder.directory(new File(System.getProperty("user.dir")));
            processBuilder.redirectErrorStream(false);
            Process process = processBuilder.start();
            String stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            String stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "命令执行超时（" + timeout + "秒）\n已输出:\n" + stdout;
            }
            int exitCode = process.exitValue();
            StringBuilder result = new StringBuilder();
            result.append("Exit code: ").append(exitCode).append("\n");
            if (!stdout.isEmpty()) result.append("STDOUT:\n").append(stdout).append("\n");
            if (!stderr.isEmpty()) result.append("STDERR:\n").append(stderr).append("\n");
            return result.toString().trim();
        } catch (Exception e) {
            log.error("bash 命令执行失败", e);
            return "命令执行失败: " + e.getMessage();
        }
    }

    @Tool(name = "read_file", description = "读取文件内容，支持可选的 limit 参数 / Read file with optional limit")
    public String readFile(
            @ToolParam(description = "文件路径") String filePath,
            @ToolParam(description = "读取的行数限制，不指定则读取全部") Integer limit) {
        log.info("EXECUTE Tool: read_file: {}", filePath);
        try {
            Path path = Paths.get(filePath).normalize();
            if (!Files.exists(path)) return "文件不存在: " + filePath;
            if (!Files.isReadable(path)) return "文件不可读: " + filePath;
            if (limit != null && limit > 0) {
                java.util.List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
                long totalLines = allLines.size();
                long actualLimit = Math.min(limit, totalLines);
                String content = allLines.subList(0, (int) actualLimit).stream().collect(Collectors.joining("\n"));
                if (totalLines > limit) content += "\n\n... (文件共 " + totalLines + " 行，仅显示前 " + limit + " 行)";
                return content;
            } else {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("读取文件失败", e);
            return "读取文件失败: " + e.getMessage();
        }
    }

    @Tool(name = "write_file", description = "写入文件（覆盖）/ Write (overwrite) file")
    public String writeFile(
            @ToolParam(description = "文件路径") String filePath,
            @ToolParam(description = "文件内容") String content) {
        log.info("EXECUTE Tool: write_file: {}", filePath);
        try {
            Path path = Paths.get(filePath).normalize();
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "文件写入成功: " + path.toAbsolutePath() + " (大小: " + content.length() + " 字符)";
        } catch (Exception e) {
            log.error("写入文件失败", e);
            return "写入文件失败: " + e.getMessage();
        }
    }

    @Tool(name = "edit_file", description = "精确文本替换（替换文件中的第一个匹配项）/ Exact text replacement (replaces first match)")
    public String editFile(
            @ToolParam(description = "文件路径") String filePath,
            @ToolParam(description = "要替换的原始文本") String oldString,
            @ToolParam(description = "替换后的新文本") String newString) {
        log.info("EXECUTE Tool: edit_file: {}", filePath);
        try {
            Path path = Paths.get(filePath).normalize();
            if (!Files.exists(path)) return "文件不存在: " + filePath;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (!content.contains(oldString)) return "替换失败: 文件中未找到匹配的文本";
            String newContent = content.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));
            Files.writeString(path, newContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "替换成功: " + path.toAbsolutePath();
        } catch (Exception e) {
            log.error("编辑文件失败", e);
            return "编辑文件失败: " + e.getMessage();
        }
    }
}
