设计二级缓存starter

引入包：

        <!--     Redis  -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!--    Redisson    -->
        <dependency>
            <groupId>org.redisson</groupId>
            <artifactId>redisson-spring-boot-starter</artifactId>
            <version>3.24.3</version>
        </dependency>

        <!--    Caffeine    -->
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
            <version>3.1.8</version>
        </dependency>

        <!--    JetCache    -->
        <dependency>
            <groupId>com.alicp.jetcache</groupId>
            <artifactId>jetcache-starter-redisson</artifactId>
            <version>2.7.5</version>
            <exclusions>
                <exclusion>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-logging</artifactId>
                </exclusion>
            </exclusions>
        </dependency>

配置参考：

```yml
spring:
  data:
    redis:
      host: 192.168.0.1
      port: 6379
      password: roydon#Mysql
      ssl:
        enabled: true
  redis:
    redisson:
      config: |
        singleServerConfig:
          idleConnectionTimeout: 10000
          connectTimeout: 10000
          timeout: 3000
          retryAttempts: 3
          retryInterval: 1500
          password: roydon#Mysql  #首次启动前务必修改成你自己的,并删除这段注解！！！
          subscriptionsPerConnection: 5
          clientName: null
          address: "redis://192.168.0.1:6379"   #首次启动前务必修改成你自己的,并删除这段注解！！！
          subscriptionConnectionMinimumIdleSize: 1
          subscriptionConnectionPoolSize: 50
          connectionMinimumIdleSize: 24
          connectionPoolSize: 64
          database: 0
          dnsMonitoringInterval: 5000
        threads: 16
        nettyThreads: 32
        codec: !<org.redisson.codec.JsonJacksonCodec> {}
        transportMode: "NIO"
jetcache:
  statIntervalMinutes: 1
  areaInCacheName: false
  local:
    default:
      type: caffeine
      keyConvertor: fastjson2
  remote:
    default:
      type: redisson
      keyConvertor: fastjson2
      broadcastChannel: ${spring.application.name}
      keyPrefix: ${spring.application.name}
      valueEncoder: java
      valueDecoder: java
      defaultExpireInMillis: 5000
```

```
@Configuration
@EnableMethodCache(basePackages = "com.roydon.dear")
public class CacheConfiguration {
}
```
```
public class CacheConstant {

    /**
     * 缓存key分隔符
     */
    public static final String CACHE_KEY_SEPARATOR = ":";
}
```
使用教程：
```
    @Cached(name = ":user:cache:id:", cacheType = CacheType.BOTH, key = "#userId", cacheNullValue = true)
    @CacheRefresh(refresh = 60, timeUnit = TimeUnit.MINUTES)
    public User findById(Long userId) {
        return userMapper.findById(userId);
    }
```
