package com.roydon.dear.common.manager;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AgentTaskManager implements InitializingBean, DisposableBean {

    private static final String TASK_KEY_PREFIX = "agent:task:";
    private static final String STOP_TOPIC_NAME = "agent:stop";
    private static final long TASK_TTL_MINUTES = 30;
    private static final long TTL_REFRESH_INTERVAL_MINUTES = 5;

    private final String instanceId;
    private final RedissonClient redissonClient;
    private final RTopic stopTopic;
    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ttlRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-ttl-refresh");
        t.setDaemon(true);
        return t;
    });

    private int listenerId;

    public AgentTaskManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);
        this.stopTopic = redissonClient.getTopic(STOP_TOPIC_NAME, StringCodec.INSTANCE);
        log.info("AgentTaskManager 初始化, instanceId={}", instanceId);
    }

    private RBucket<String> getTaskBucket(String conversationId) {
        return redissonClient.getBucket(TASK_KEY_PREFIX + conversationId, StringCodec.INSTANCE);
    }

    @Override
    public void afterPropertiesSet() {
        listenerId = stopTopic.addListener(String.class, (channel, conversationId) -> {
            handleRemoteStop(conversationId);
        });
        ttlRefreshScheduler.scheduleAtFixedRate(
                this::refreshTaskTtls,
                TTL_REFRESH_INTERVAL_MINUTES,
                TTL_REFRESH_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        log.info("AgentTaskManager 启动完成, 已订阅停止主题, TTL刷新间隔={}分钟", TTL_REFRESH_INTERVAL_MINUTES);
    }

    @Override
    public void destroy() {
        try { stopTopic.removeListener(listenerId); } catch (Exception e) { log.warn("移除发布订阅监听器失败", e); }
        ttlRefreshScheduler.shutdown();
        for (String conversationId : taskMap.keySet()) {
            doRemoveTask(conversationId);
        }
        log.info("AgentTaskManager 销毁完成, instanceId={}", instanceId);
    }

    public static class TaskInfo {
        private final Sinks.Many<String> sink;
        private Disposable disposable;
        private final long createTime;
        private String agentType;

        public TaskInfo(Sinks.Many<String> sink, String agentType) {
            this.sink = sink;
            this.agentType = agentType;
            this.createTime = System.currentTimeMillis();
        }

        public Sinks.Many<String> getSink() { return sink; }
        public Disposable getDisposable() { return disposable; }
        public void setDisposable(Disposable disposable) { this.disposable = disposable; }
        public long getCreateTime() { return createTime; }
        public String getAgentType() { return agentType; }
    }

    public TaskInfo registerTask(String conversationId, Sinks.Many<String> sink, String agentType) {
        TaskInfo existing = taskMap.get(conversationId);
        if (existing != null) {
            log.warn("会话 {} 本地已有任务在执行，拒绝注册新任务", conversationId);
            return null;
        }
        RBucket<String> bucket = getTaskBucket(conversationId);
        boolean acquired = bucket.trySet(instanceId, TASK_TTL_MINUTES, TimeUnit.MINUTES);
        if (!acquired) {
            String holder = bucket.get();
            log.warn("会话 {} 已在实例 {} 上执行，当前实例 {} 拒绝注册", conversationId, holder, instanceId);
            return null;
        }
        TaskInfo taskInfo = new TaskInfo(sink, agentType);
        taskMap.put(conversationId, taskInfo);
        log.info("注册任务成功: conversationId={}, agentType={}, instanceId={}", conversationId, agentType, instanceId);
        return taskInfo;
    }

    public void setDisposable(String conversationId, Disposable disposable) {
        TaskInfo taskInfo = taskMap.get(conversationId);
        if (taskInfo != null) {
            taskInfo.setDisposable(disposable);
        }
    }

    public boolean stopTask(String conversationId) {
        TaskInfo localTask = taskMap.get(conversationId);
        if (localTask != null) {
            log.info("本地停止任务: conversationId={}, instanceId={}", conversationId, instanceId);
            doStopTask(conversationId, localTask);
            return true;
        }
        RBucket<String> bucket = getTaskBucket(conversationId);
        if (!bucket.isExists()) {
            return false;
        }
        String holder = bucket.get();
        if (instanceId.equals(holder)) {
            log.debug("任务持有者是本实例，跳过广播: conversationId={}", conversationId);
            return false;
        }
        long receivers = stopTopic.publish(conversationId);
        log.info("发布停止广播: conversationId={}, 订阅者数量={}", conversationId, receivers);
        return true;
    }

    private void handleRemoteStop(String conversationId) {
        TaskInfo taskInfo = taskMap.remove(conversationId);
        if (taskInfo == null) return;
        log.info("远程停止任务: conversationId={}, instanceId={}", conversationId, instanceId);
        doStopTask(conversationId, taskInfo);
    }

    private void doStopTask(String conversationId, TaskInfo taskInfo) {
        try {
            Disposable disposable = taskInfo.getDisposable();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
                log.info("已中断底层调用: conversationId={}", conversationId);
            }
            Sinks.Many<String> sink = taskInfo.getSink();
            if (sink != null) {
                try {
                    sink.tryEmitNext(createStopMessage());
                    sink.tryEmitComplete();
                    log.info("已发送停止消息: conversationId={}", conversationId);
                } catch (Exception e) {
                    log.warn("发送停止消息失败: conversationId={}", conversationId, e);
                }
            }
        } finally {
            doRemoveTask(conversationId);
        }
    }

    private void doRemoveTask(String conversationId) {
        taskMap.remove(conversationId);
        RBucket<String> bucket = getTaskBucket(conversationId);
        String holder = bucket.get();
        if (instanceId.equals(holder)) {
            bucket.delete();
            log.debug("删除 Redis 任务key: conversationId={}", conversationId);
        }
    }

    public boolean hasRunningTask(String conversationId) {
        if (taskMap.containsKey(conversationId)) return true;
        RBucket<String> bucket = getTaskBucket(conversationId);
        return bucket.isExists();
    }

    private void refreshTaskTtls() {
        if (taskMap.isEmpty()) return;
        log.debug("开始刷新 TTL, 本地任务数={}", taskMap.size());
        for (String conversationId : taskMap.keySet()) {
            try {
                RBucket<String> bucket = getTaskBucket(conversationId);
                String holder = bucket.get();
                if (instanceId.equals(holder)) {
                    bucket.expire(Duration.ofMinutes(TASK_TTL_MINUTES));
                } else {
                    log.warn("TTL刷新发现 key 归属变化: conversationId={}, 期望={}, 实际={}",
                            conversationId, instanceId, holder);
                    taskMap.remove(conversationId);
                }
            } catch (Exception e) {
                log.error("TTL刷新失败: conversationId={}", conversationId, e);
            }
        }
    }

    private String createStopMessage() {
        JSONObject obj = new JSONObject();
        obj.put("type", "text");
        obj.put("content", "\n⏹ 用户已停止生成\n");
        return JSON.toJSONString(obj);
    }
}
