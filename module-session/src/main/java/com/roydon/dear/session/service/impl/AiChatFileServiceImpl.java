package com.roydon.dear.session.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.session.entity.AiChatFile;
import com.roydon.dear.session.mapper.AiChatFileMapper;
import com.roydon.dear.session.service.IAiChatFileService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;


/**
 * 文件元数据表，存储文件基本信息和解析后的内容(AiChatFile)表服务实现类
 *
 * @author roydon
 * @since 2026-05-20 21:41:50
 */
@Service
public class AiChatFileServiceImpl extends ServiceImpl<AiChatFileMapper, AiChatFile> implements IAiChatFileService {
    @Resource
    private AiChatFileMapper aiChatFileMapper;

    @Override
    public List<AiChatFile> getListByIds(List<Long> ids) {
        return aiChatFileMapper.selectBatchIds(ids);
    }

    @Override
    public List<AiChatFile> getListByIds(String ids) {
        List<Long> fileIdsList = Arrays.stream(ids.split(",")).map(Long::parseLong).toList();
        return aiChatFileMapper.selectBatchIds(fileIdsList);
    }

}
