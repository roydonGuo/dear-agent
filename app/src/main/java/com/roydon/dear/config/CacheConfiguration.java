package com.roydon.dear.config;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableMethodCache(basePackages = "com.roydon.dear")
public class CacheConfiguration {
}
