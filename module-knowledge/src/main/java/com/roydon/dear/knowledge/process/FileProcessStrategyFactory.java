package com.roydon.dear.knowledge.process;

import com.roydon.dear.knowledge.enums.FileMineType;
import groovy.io.FileType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文件处理策略工厂，用来生产策略
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/20
 **/
@Service
public class FileProcessStrategyFactory {

    @Autowired
    private List<FileProcessStrategy> fileProcessStrategyList;

    public FileProcessStrategy get(FileMineType fileMineType) {
        return fileProcessStrategyList.stream()
                .filter(service -> service.supports(fileMineType))
                .findFirst()
                .orElse(null);
    }
}
