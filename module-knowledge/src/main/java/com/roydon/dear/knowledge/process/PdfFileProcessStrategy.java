package com.roydon.dear.knowledge.process;

import com.roydon.dear.common.exception.BusinessException;
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
    public KnowledgeFileDO processFile(KnowledgeFileDO fileDO) {
        log.info("开始处理 pdf 文件: {}", fileDO.getName());
        // todo
        throw new BusinessException("暂不支持pdf文件处理");
    }

}
