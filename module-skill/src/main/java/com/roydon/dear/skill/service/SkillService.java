package com.roydon.dear.skill.service;

import com.roydon.dear.skill.model.Skill;

import java.io.IOException;
import java.util.List;

public interface SkillService {

    List<Skill> listAll();

    Skill getByName(String name);

    Skill create(Skill skill) throws IOException;

    Skill update(String name, Skill skill) throws IOException;

    boolean delete(String name);

    Skill toggleEnabled(String name) throws IOException;

    List<Skill> listEnabled();
}
