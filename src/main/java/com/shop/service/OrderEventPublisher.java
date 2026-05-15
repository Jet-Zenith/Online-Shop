package com.shop.service;

import com.shop.dto.OrderDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class OrderEventPublisher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final String streamKey;

    public OrderEventPublisher(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${app.events.order-stream-key:stream:orders}") String streamKey) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
    }

    public void publishOrderCreated(OrderDTO order) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "ORDER_CREATED");
        event.put("orderId", order.getId());
        event.put("orderNo", order.getOrderNo());
        event.put("userId", order.getUserId());
        event.put("totalAmount", order.getTotalAmount().toPlainString());
        event.put("totalQuantity", String.valueOf(order.getTotalQuantity()));
        event.put("status", order.getStatus());
        redisTemplate.opsForStream().add(streamKey, event);
        log.info("Published order event {} for order {}", "ORDER_CREATED", order.getOrderNo());
    }
}
