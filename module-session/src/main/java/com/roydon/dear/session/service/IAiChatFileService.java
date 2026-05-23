package com.roydon.dear.session.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.session.entity.AiChatFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.net.URISyntaxException;
import java.util.List;

/**
 * 文件元数据表，存储文件基本信息和解析后的内容(AiChatFile)表服务接口
 *
 * @author roydon
 * @since 2026-05-20 21:41:50
 */
public interface IAiChatFileService extends IService<AiChatFile> {

    List<AiChatFile> getListByIds(String ids);

}
