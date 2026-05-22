package com.shop.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class DistributedLockService {

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final RedisTemplate<String, Object> redisTemplate;

    public DistributedLockService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取分布式锁（非阻塞模式）
     * 采用 Redis 的 SETNX (Set if Not eXists) 指令，保证高并发下的绝对互斥。
     *
     * @param key   锁的唯一标识（如：checkout:lock:userId）
     * @param token 锁的身份指纹（必须是唯一的 UUID）。
     * 作用：防止在锁超时自动释放后，被原持有者在执行完毕时误删他人的锁。
     * @param ttl   锁的最大持有时间 (Time-To-Live)。
     * 作用：终极防死锁机制。即使应用服务器宕机崩溃未能主动释放锁，Redis 也会在超时后自动兜底清理。
     * @return true: 成功抢占到锁；false: 锁已被他人占用，或发生网络异常
     */
    public boolean tryLock(String key, String token, Duration ttl) {

        // 原子操作：当且仅当 Key 不存在时，存入 token 并设置过期时间 ttl。
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);

        // 核心防御：防止自动拆箱引发的 NullPointerException
        // 极端网络状况下 setIfAbsent 可能返回 null，此处采用 equals 进行安全比对
        return Boolean.TRUE.equals(locked);
    }

    public boolean releaseLock(String key, String token) {
        Long released = redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(key), token);
        return released != null && released > 0;
    }
}
