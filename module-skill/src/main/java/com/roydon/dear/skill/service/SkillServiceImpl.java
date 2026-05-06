package com.roydon.dear.skill.service;

import com.roydon.dear.skill.model.Skill;
import com.roydon.dear.skill.repository.SkillRepository;
import com.roydon.dear.skill.tool.SkillsTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillServiceImpl implements SkillService {

    private final SkillRepository repository;
    private final SkillsTool skillsTool;

    @Override
    public List<Skill> listAll() {
        return repository.listAll();
    }

    @Override
    public Skill getByName(String name) {
        return repository.getByName(name);
    }

    @Override
    public Skill create(Skill skill) throws IOException {
        if (skill.getName() == null || skill.getName().isBlank()) {
            throw new IllegalArgumentException("Skill name 不能为空");
        }
        if (!skill.getName().matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Skill name 只能包含小写字母、数字和短横线");
        }
        if (skill.getName().length() > 64) {
            throw new IllegalArgumentException("Skill name 不能超过 64 个字符");
        }
        Skill existing = repository.getByName(skill.getName());
        if (existing != null) {
            throw new IllegalArgumentException("Skill 已存在: " + skill.getName());
        }
        Skill saved = repository.save(skill);
        skillsTool.refresh();
        return saved;
    }

    @Override
    public Skill update(String name, Skill skill) throws IOException {
        Skill existing = repository.getByName(name);
        if (existing == null) {
            throw new IllegalArgumentException("Skill 不存在: " + name);
        }
        skill.setName(name);
        skill.setCreateTime(existing.getCreateTime());
        Skill updated = repository.save(skill);
        skillsTool.refresh();
        return updated;
    }

    @Override
    public boolean delete(String name) {
        boolean result = repository.delete(name);
        if (result) {
            skillsTool.refresh();
        }
        return result;
    }

    @Override
    public Skill toggleEnabled(String name) throws IOException {
        Skill existing = repository.getByName(name);
        if (existing == null) {
            throw new IllegalArgumentException("Skill 不存在: " + name);
        }
        Skill updated = repository.toggleEnabled(name);
        skillsTool.refresh();
        return updated;
    }

    @Override
    public List<Skill> listEnabled() {
        return repository.listEnabled();
    }
}
