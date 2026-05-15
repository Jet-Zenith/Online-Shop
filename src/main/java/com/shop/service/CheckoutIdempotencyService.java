package com.shop.service;

import com.shop.dto.OrderDTO;
import com.shop.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class CheckoutIdempotencyService {

    private static final String KEY_PREFIX = "idempotency:checkout:";
    private static final String PROCESSING = "PROCESSING";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;

    public CheckoutIdempotencyService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isEnabled(String idempotencyKey) {
        return StringUtils.isNotBlank(idempotencyKey);
    }

    public Optional<OrderDTO> findCompleted(String userId, String idempotencyKey) {
        Object value = redisTemplate.opsForValue().get(buildKey(userId, idempotencyKey));
        if (value instanceof OrderDTO orderDTO) {
            return Optional.of(orderDTO);
        }
        return Optional.empty();
    }

    public void begin(String userId, String idempotencyKey) {
        String key = buildKey(userId, idempotencyKey);
        Boolean created = redisTemplate.opsForValue().setIfAbsent(key, PROCESSING, TTL);
        if (Boolean.TRUE.equals(created)) {
            return;
        }
        Object value = redisTemplate.opsForValue().get(key);
        if (value instanceof OrderDTO) {
            return;
        }
        throw BusinessException.conflict("Checkout request is already processing");
    }

    public void complete(String userId, String idempotencyKey, OrderDTO orderDTO) {
        redisTemplate.opsForValue().set(buildKey(userId, idempotencyKey), orderDTO, TTL);
    }

    public void clear(String userId, String idempotencyKey) {
        redisTemplate.delete(buildKey(userId, idempotencyKey));
    }

    private String buildKey(String userId, String idempotencyKey) {
        return KEY_PREFIX + userId + ":" + idempotencyKey;
    }
}
