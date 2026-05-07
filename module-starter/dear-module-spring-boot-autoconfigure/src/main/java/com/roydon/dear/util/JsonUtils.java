package com.roydon.dear.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;

import java.util.List;

/**
 * JSON 工具类 (基于 FastJSON2)
 */
public class JsonUtils {

    public static String toJsonString(Object obj) {
        return JSON.toJSONString(obj);
    }

    public static <T> T parseObject(String text, Class<T> clazz) {
        return JSON.parseObject(text, clazz);
    }

    public static <T> List<T> parseArray(String text, Class<T> clazz) {
        return JSON.parseArray(text, clazz);
    }
}
