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

    /**
     * 发布“订单已创建”事件。
     * <p>
     * 这个方法主要服务于非 Outbox 模式或需要直接发布订单事件的场景：
     * 它先把订单 DTO 转换成标准事件对象，再交给统一的 publish 方法选择底层投递通道。
     *
     * @param order 已创建成功的订单快照
     */
    public void publishOrderCreated(OrderDTO order) {
        publish(toOrderCreatedEvent(order));
    }

    /**
     * 统一事件发布入口。
     * <p>
     * 上层业务不需要关心底层使用 RocketMQ 还是 Redis Stream，只需要传入 OrderCreatedEvent。
     * 具体通道由 app.events.backend 配置决定：rocketmq 走 RocketMQ，否则走 Redis Stream。
     *
     * @param event 待投递的订单创建事件
     */
    public void publish(OrderCreatedEvent event) {
        // 1. 优先读取配置中的事件后端。默认 backend=rocketmq，用于企业级异步解耦。
        if (BACKEND_ROCKETMQ.equalsIgnoreCase(properties.getBackend())) {
            publishToRocketMq(event);
        } else {
            // 2. 非 RocketMQ 配置时，使用 Redis Stream 作为轻量级消息通道。
            publishToRedisStream(event);
        }
    }

    /**
     * 将订单事件投递到 RocketMQ。
     * <p>
     * RocketMQ 是主消息队列方案，适合订单创建后的库存、积分、通知、履约等异步扩展。
     * 如果当前环境没有注入 RocketMQTemplate，则降级写入 Redis Stream，避免事件直接丢失。
     *
     * @param event 待投递的订单创建事件
     */
    private void publishToRocketMq(OrderCreatedEvent event) {
        // 1. RocketMQTemplate 可能因为本地未配置 RocketMQ 而不存在，所以这里用 ObjectProvider 懒获取。
        RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        if (rocketMQTemplate == null) {
            // 2. RocketMQ 不可用时不直接失败，而是降级到 Redis Stream，保证事件仍有兜底通道。
            log.warn("RocketMQTemplate is unavailable, fallback to Redis Stream for order {}", event.getOrderNo());
            publishToRedisStream(event);
            return;
        }

        // 3. RocketMQ destination 格式为 topic:tag。
        // topic 用于归类订单事件，tag 用于让消费者只订阅 ORDER_CREATED 这类事件。
        String destination = properties.getOrderTopic() + ":" + properties.getOrderCreatedTag();

        // 4. syncSend 表示同步发送：当前线程会等待 Broker 返回发送结果。
        // KEYS 使用 orderNo，方便 RocketMQ 控制台按业务订单号检索消息；eventId 用于下游幂等消费。
        rocketMQTemplate.syncSend(destination, MessageBuilder
                .withPayload(event)
                .setHeader("KEYS", event.getOrderNo())
                .setHeader("eventId", event.getEventId())
                .build());
        log.info("Published order event {} to RocketMQ topic {}", event.getEventId(), destination);
    }

    /**
     * 将订单事件写入 Redis Stream。
     * <p>
     * Redis Stream 在这里承担两种角色：
     * 1. 当 app.events.backend=redis-stream 时，作为主动选择的轻量消息队列。
     * 2. 当 RocketMQTemplate 不可用时，作为兜底投递通道。
     *
     * @param event 待投递的订单创建事件
     */
    private void publishToRedisStream(OrderCreatedEvent event) {
        // Redis Stream 以 Map 形式保存消息体，所以需要先把事件对象转换为扁平字段。
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
