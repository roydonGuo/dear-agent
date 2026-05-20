package com.roydon.dear.session.service.impl;

import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.session.entity.AiChatFile;
import com.roydon.dear.session.mapper.AiChatFileMapper;
import com.roydon.dear.session.service.IAiChatFileService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * 文件元数据表，存储文件基本信息和解析后的内容(AiChatFile)表服务实现类
 *
 * @author roydon
 * @since 2026-05-20 21:41:50
 */
@Service
public class AiChatFileServiceImpl extends ServiceImpl<AiChatFileMapper, AiChatFile> implements IAiChatFileService {
    public static final String CACHE_NAME = "chatFile:";
    public static final String CACHE_NAME_LIST = "list_by_ids:";

    @Resource
    private AiChatFileMapper aiChatFileMapper;

    @Override
    @Cached(name = CACHE_NAME + CACHE_NAME_LIST, key = "#ids", cacheType = CacheType.BOTH, cacheNullValue = true, expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public List<AiChatFile> getListByIds(String ids) {
        List<Long> fileIdsList = Arrays.stream(ids.split(",")).map(Long::parseLong).toList();
        return aiChatFileMapper.selectBatchIds(fileIdsList);
    }

}
