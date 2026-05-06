package com.roydon.dear.skill.repository;

import com.roydon.dear.skill.model.Skill;
import com.roydon.dear.skill.model.SkillFrontMatter;
import com.roydon.dear.skill.model.SkillType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.roydon.dear.skill.model.SkillParameter;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 文件系统 Skill 存储层，操作 SKILL.md 文件。
 *
 * <pre>
 * ~/.dear-agent/.skills/
 *   weather/
 *     SKILL.md
 *   translate/
 *     SKILL.md
 * </pre>
 */
@Slf4j
@Component
public class SkillRepository {

    private static final String SKILLS_DIR = System.getProperty("user.home") + "/.dear-agent/.skills";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String YAML_SEPARATOR = "---";

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final Yaml yaml;

    public SkillRepository() {
        // 宽松解析：先读为 Map，手动映射 — 自动忽略未知字段
        this.yaml = new Yaml();
    }

    // ---- 目录 ----

    private Path ensureDir() throws IOException {
        Path dir = Paths.get(SKILLS_DIR);
        Files.createDirectories(dir);
        return dir;
    }

    // ---- 列表 ----

    public List<Skill> listAll() {
        lock.readLock().lock();
        try {
            Path dir = ensureDir();
            List<Skill> skills = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
                for (Path skillDir : stream) {
                    Path mdFile = skillDir.resolve(SKILL_FILE);
                    if (Files.exists(mdFile)) {
                        try {
                            Skill skill = readSkill(mdFile);
                            if (skill != null) {
                                skills.add(skill);
                            }
                        } catch (Exception e) {
                            log.error("读取 Skill 文件失败: {}", mdFile, e);
                        }
                    }
                }
            }
            skills.sort(Comparator.comparing(Skill::getUpdateTime,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            return skills;
        } catch (IOException e) {
            log.error("列出 Skills 失败", e);
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---- 获取 ----

    public Skill getByName(String name) {
        lock.readLock().lock();
        try {
            Path mdFile = Paths.get(SKILLS_DIR, name, SKILL_FILE);
            if (Files.exists(mdFile)) {
                return readSkill(mdFile);
            }
            return null;
        } catch (IOException e) {
            log.error("读取 Skill 失败: {}", name, e);
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---- 保存 ----

    public Skill save(Skill skill) throws IOException {
        lock.writeLock().lock();
        try {
            Path dir = ensureDir();
            Path skillDir = dir.resolve(skill.getName());
            Files.createDirectories(skillDir);

            LocalDateTime now = LocalDateTime.now();
            if (skill.getCreateTime() == null) {
                skill.setCreateTime(now);
            }
            skill.setUpdateTime(now);

            String content = toSkillMd(skill);
            Path mdFile = skillDir.resolve(SKILL_FILE);
            Files.writeString(mdFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Skill 已保存: {} ({})", skill.getName(), mdFile);
            return skill;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- 删除 ----

    public boolean delete(String name) {
        lock.writeLock().lock();
        try {
            Path skillDir = Paths.get(SKILLS_DIR, name);
            if (!Files.exists(skillDir)) return false;
            Files.walkFileTree(skillDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Skill 已删除: {}", name);
            return true;
        } catch (IOException e) {
            log.error("删除 Skill 失败: {}", name, e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- 启用/禁用 ----

    public Skill toggleEnabled(String name) throws IOException {
        lock.writeLock().lock();
        try {
            Skill skill = getByName(name);
            if (skill == null) return null;
            skill.setEnabled(!skill.isEnabled());
            return save(skill);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- 已启用列表 ----

    public List<Skill> listEnabled() {
        return listAll().stream().filter(Skill::isEnabled).toList();
    }

    public Path getSkillDir(String name) {
        return Paths.get(SKILLS_DIR, name);
    }

    // ---- SKILL.md ↔ Skill 转换 ----

    Skill readSkill(Path mdFile) throws IOException {
        String content = Files.readString(mdFile, StandardCharsets.UTF_8);

        // 提取创建/修改时间
        BasicFileAttributes attrs = Files.readAttributes(mdFile, BasicFileAttributes.class);
        LocalDateTime createTime = attrs.creationTime().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        LocalDateTime updateTime = attrs.lastModifiedTime().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();

        // 解析 YAML frontmatter + Markdown body
        ParsedSkillMd parsed = parseSkillMd(content);
        SkillFrontMatter fm = parsed.frontMatter;

        return Skill.builder()
                .name(fm.getName())
                .description(fm.getDescription())
                .version(fm.getVersion() != null ? fm.getVersion() : "1.0.0")
                .author(fm.getAuthor())
                .type(fm.getType())
                .entry(fm.getEntry())
                .parameters(fm.getParameters() != null ? fm.getParameters() : Collections.emptyList())
                .enabled(fm.isEnabled())
                .body(parsed.body)
                .createTime(createTime)
                .updateTime(updateTime)
                .build();
    }

    @SuppressWarnings("unchecked")
    ParsedSkillMd parseSkillMd(String content) {
        ParsedSkillMd result = new ParsedSkillMd();
        if (content == null || content.isBlank()) {
            result.frontMatter = new SkillFrontMatter();
            result.body = "";
            return result;
        }

        String trimmed = content.strip();
        if (trimmed.startsWith(YAML_SEPARATOR)) {
            int endIdx = trimmed.indexOf(YAML_SEPARATOR, 3);
            if (endIdx > 3) {
                String yamlStr = trimmed.substring(3, endIdx).strip();
                // 解析为 Map，自动忽略未知字段
                Map<String, Object> map = yaml.loadAs(yamlStr, Map.class);
                result.frontMatter = mapToFrontMatter(map != null ? map : Map.of());
                result.body = trimmed.substring(endIdx + 3).strip();
            } else {
                result.frontMatter = new SkillFrontMatter();
                result.body = trimmed;
            }
        } else {
            result.frontMatter = new SkillFrontMatter();
            result.body = trimmed;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private SkillFrontMatter mapToFrontMatter(Map<String, Object> map) {
        SkillFrontMatter fm = new SkillFrontMatter();
        fm.setName(str(map, "name"));
        fm.setDescription(str(map, "description"));
        fm.setVersion(str(map, "version"));
        fm.setAuthor(str(map, "author"));
        String typeStr = str(map, "type");
        if (typeStr != null) {
            try {
                fm.setType(SkillType.valueOf(typeStr.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        fm.setEntry(str(map, "entry"));
        fm.setEnabled(map.get("enabled") instanceof Boolean b ? b : true);

        // 解析 parameters
        Object paramsObj = map.get("parameters");
        if (paramsObj instanceof List<?> list) {
            List<SkillParameter> parameters = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> paramMap) {
                    Map<String, Object> pm = (Map<String, Object>) paramMap;
                    SkillParameter sp = new SkillParameter();
                    sp.setName(str(pm, "name"));
                    sp.setType(str(pm, "type"));
                    sp.setRequired(pm.get("required") instanceof Boolean b ? b : false);
                    sp.setDescription(str(pm, "description"));
                    sp.setDefaultValue(str(pm, "defaultValue"));
                    parameters.add(sp);
                }
            }
            fm.setParameters(parameters);
        }
        return fm;
    }

    private String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    String toSkillMd(Skill skill) {
        // 构建 frontmatter map（只序列化非空字段）
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("name", skill.getName());
        fm.put("description", skill.getDescription());
        if (skill.getVersion() != null && !"1.0.0".equals(skill.getVersion())) {
            fm.put("version", skill.getVersion());
        }
        if (skill.getAuthor() != null && !skill.getAuthor().isBlank()) {
            fm.put("author", skill.getAuthor());
        }
        if (skill.getType() != null) {
            fm.put("type", skill.getType().name().toLowerCase());
        }
        if (skill.getEntry() != null && !skill.getEntry().isBlank()) {
            fm.put("entry", skill.getEntry());
        }
        if (skill.getParameters() != null && !skill.getParameters().isEmpty()) {
            fm.put("parameters", skill.getParameters());
        }
        fm.put("enabled", skill.isEnabled());

        // 序列化 YAML frontmatter
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml dumper = new Yaml(dumperOptions);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append(dumper.dump(fm).stripTrailing());
        sb.append("\n---\n");

        if (skill.getBody() != null && !skill.getBody().isBlank()) {
            sb.append("\n");
            sb.append(skill.getBody());
            sb.append("\n");
        }
        return sb.toString();
    }

    // ---- 内部类 ----

    static class ParsedSkillMd {
        SkillFrontMatter frontMatter;
        String body;
    }
}
