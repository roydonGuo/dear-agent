package com.roydon.dear.knowledge.process;

import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.enums.FileMineType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * pdf文件处理服务
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/20
 **/
@Slf4j
@Service
public class PdfFileProcessStrategy implements FileProcessStrategy {

    /**
     * 判断是否支持该文件
     *
     * @param mineType
     */
    @Override
    public boolean supports(FileMineType mineType) {
        return FileMineType.APPLICATION_PDF == mineType;
    }

    /**
     * 处理文档转换 - pdf 格式
     */
    @Override
    public void processFile(KnowledgeFileDO fileDO, InputStream inputStream) {

    }

}
