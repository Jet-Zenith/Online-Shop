package com.shop.service;

import com.shop.common.RequestContext;
import com.shop.dto.OrderDTO;
import com.shop.event.OrderCreatedEvent;
import com.shop.event.OrderEventProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class OrderEventPublisher {

    private static final String BACKEND_ROCKETMQ = "rocketmq";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    private final OrderEventProperties properties;

    public OrderEventPublisher(RedisTemplate<String, Object> redisTemplate,
                               ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider,
                               OrderEventProperties properties) {
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplateProvider = rocketMQTemplateProvider;
        this.properties = properties;
    }

    public void publishOrderCreated(OrderDTO order) {
        publish(toOrderCreatedEvent(order));
    }

    public void publish(OrderCreatedEvent event) {
        if (BACKEND_ROCKETMQ.equalsIgnoreCase(properties.getBackend())) {
            publishToRocketMq(event);
        } else {
            publishToRedisStream(event);
        }
    }

    private void publishToRocketMq(OrderCreatedEvent event) {
        RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        if (rocketMQTemplate == null) {
            log.warn("RocketMQTemplate is unavailable, fallback to Redis Stream for order {}", event.getOrderNo());
            publishToRedisStream(event);
            return;
        }

        String destination = properties.getOrderTopic() + ":" + properties.getOrderCreatedTag();
        rocketMQTemplate.syncSend(destination, MessageBuilder
                .withPayload(event)
                .setHeader("KEYS", event.getOrderNo())
                .setHeader("eventId", event.getEventId())
                .build());
        log.info("Published order event {} to RocketMQ topic {}", event.getEventId(), destination);
    }

    private void publishToRedisStream(OrderCreatedEvent event) {
        redisTemplate.opsForStream().add(properties.getOrderStreamKey(), toMap(event));
        log.info("Published order event {} to Redis Stream {}", event.getEventId(), properties.getOrderStreamKey());
    }

    private OrderCreatedEvent toOrderCreatedEvent(OrderDTO order) {
        return OrderCreatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("ORDER_CREATED")
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .totalQuantity(order.getTotalQuantity())
                .status(order.getStatus())
                .occurredAt(LocalDateTime.now())
                .traceId(RequestContext.getTraceId())
                .build();
    }

    private Map<String, Object> toMap(OrderCreatedEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.getEventId());
        body.put("eventType", event.getEventType());
        body.put("orderId", event.getOrderId());
        body.put("orderNo", event.getOrderNo());
        body.put("userId", event.getUserId());
        body.put("totalAmount", event.getTotalAmount().toPlainString());
        body.put("totalQuantity", String.valueOf(event.getTotalQuantity()));
        body.put("status", event.getStatus());
        body.put("occurredAt", String.valueOf(event.getOccurredAt()));
        body.put("traceId", event.getTraceId());
        return body;
    }
}
