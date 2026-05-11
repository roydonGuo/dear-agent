package com.roydon.dear.web.controller;

import com.roydon.dear.common.BaseResult;
import com.roydon.dear.core.service.FileStorage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/file")
public class FileController {

    private final FileStorage fileStorage;

    public FileController(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @PostMapping("/upload")
    public BaseResult<String> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "prefix", defaultValue = "uploads") String prefix) {
        if (file.isEmpty()) {
            return BaseResult.newError("文件不能为空");
        }
        String url = fileStorage.upload(file, prefix);
        return BaseResult.newSuccess(url);
    }
}
