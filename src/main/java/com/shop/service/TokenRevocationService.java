package com.shop.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class TokenRevocationService {

    private static final String REVOKED_TOKEN_PREFIX = "jwt:revoked:";

    private final RedisTemplate<String, Object> redisTemplate;

    public TokenRevocationService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void revoke(String tokenId, long expiresAtEpochSeconds) {
        long ttlSeconds = expiresAtEpochSeconds - Instant.now().getEpochSecond();
        if (ttlSeconds <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(key(tokenId), "revoked", Duration.ofSeconds(ttlSeconds));
    }

    public boolean isRevoked(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(tokenId)));
    }

    private String key(String tokenId) {
        return REVOKED_TOKEN_PREFIX + tokenId;
    }
}
