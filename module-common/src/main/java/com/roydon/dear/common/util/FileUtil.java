package com.roydon.dear.common.util;

/**
 * FileUtil
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/23
 **/
public class FileUtil {
    /**
     * 获取文件后缀名
     *
     * @param fileName 文件名
     * @return 文件后缀名
     */
    public static String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) {
            return "";
        }
        return fileName.substring(dotIndex + 1);
    }
}
