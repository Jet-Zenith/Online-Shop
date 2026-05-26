package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.RequestContext;
import com.shop.dto.OrderDTO;
import com.shop.event.OrderCreatedEvent;
import com.shop.mapper.EventOutboxMapper;
import com.shop.model.EventOutbox;
import com.shop.model.EventOutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class OrderOutboxService {

    private final EventOutboxMapper eventOutboxMapper;
    private final ObjectMapper objectMapper;
    private final OrderEventPublisher orderEventPublisher;
    private final boolean enabled;
    private final int batchSize;
    private final int maxRetries;

    public OrderOutboxService(EventOutboxMapper eventOutboxMapper,
                              ObjectMapper objectMapper,
                              OrderEventPublisher orderEventPublisher,
                              @Value("${app.outbox.enabled:true}") boolean enabled,
                              @Value("${app.outbox.batch-size:20}") int batchSize,
                              @Value("${app.outbox.max-retries:5}") int maxRetries) {
        this.eventOutboxMapper = eventOutboxMapper;
        this.objectMapper = objectMapper;
        this.orderEventPublisher = orderEventPublisher;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    /**
     * 保存“订单已创建”领域事件 (基于 Transactional Outbox 模式)
     * <p>
     * 架构意图与核心设计：
     * 1. 【平滑降级】：通过 enabled 开关，支持在直发 MQ (低延迟但有丢失风险) 与本地消息表 (强一致性) 之间动态热切换。
     * 2. 【绝对一致性】：当前方法必须运行在父级的 @Transactional 事务中。订单数据的落盘与 Outbox 事件的落盘
     * 共享同一个底层数据库连接。同生共死，从物理层面根除分布式系统中的“双写不一致”问题。
     * 3. 【可追踪与幂等】：统一注入 traceId 保证全链路可观测；生成唯一 eventId 供下游微服务做重复消费拦截。
     * <p>
     * - 这里不是马上把消息发给 RocketMQ，而是先把“待发送的事件”写入 event_outbox 表。
     * - 因为它和订单创建共用同一个数据库事务，所以不会出现“订单成功了，但消息没保存下来”的断层。
     * - 后面的 relayPendingEvents 定时任务会扫描 PENDING 事件，再可靠投递到 RocketMQ/Redis Stream。
     *
     * @param order 生成成功的订单视图快照
     */
    public void saveOrderCreatedEvent(OrderDTO order) {
        if (!enabled) {
            // Outbox 被关闭时，退化为直接发布事件。这个模式延迟更低，但不具备本地消息表的可靠补偿能力。
            orderEventPublisher.publishOrderCreated(order);
            return;
        }

        // 1. 把订单快照转换为领域事件。
        // 注意：事件里只放下游关心的稳定字段，不直接传数据库实体，避免实体结构变化污染消息契约。
        OrderCreatedEvent event = OrderCreatedEvent.builder()
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

        // 2. 把领域事件序列化后写入 Outbox 表，而不是在当前事务中直接调用 MQ。
        // status=PENDING 表示“订单事务已经准备好一条待投递消息，但还没有真正发送成功”。
        // relayPendingEvents 会在事务提交后异步扫描这条记录并投递，投递成功再改成 SENT。
        EventOutbox outbox = EventOutbox.builder()
                .aggregateType("ORDER")
                .aggregateId(order.getId())
                .eventType(event.getEventType())
                .payload(toJson(event))
                .status(EventOutboxStatus.PENDING.name())
                .retryCount(0)
                .build();

        // 3. 这一次 insert 是 Outbox 模式的关键：
        // 只要外层 createOrder 事务提交，订单和事件记录会一起提交；只要事务回滚，二者会一起消失。
        // 这样就把“订单库”和“消息队列”的双写问题，转换成了单库事务内的一次可靠写入。
        eventOutboxMapper.insert(outbox);
    }

    /**
     * 定时扫描并投递本地消息表里的待发送事件。
     * <p>
     * Transactional Outbox 的完整链路分两步：
     * 1. createOrder 事务里只负责把订单和 event_outbox(PENDING) 一起写入数据库，保证不丢事件。
     * 2. 当前方法作为后台投递器，持续捞取 PENDING 事件并发送到 RocketMQ/Redis Stream。
     * <p>
     * @Scheduled 的作用：
     * - 让 Spring 定时调用这个方法，不需要外部请求触发。
     * - fixedDelayString 表示“上一次执行结束后，再等待指定时间执行下一次”。
     * - 这里默认每 5000ms 扫描一次，也可以通过 app.outbox.relay-delay-ms 配置调整。
     */
    @Scheduled(fixedDelayString = "${app.outbox.relay-delay-ms:5000}")
    @Transactional(rollbackFor = Exception.class)
    public void relayPendingEvents() {
        if (!enabled) {
            return;
        }

        // 1. 只查询还没有投递成功的 PENDING 事件，并限制重试次数，避免坏消息无限刷屏。
        LambdaQueryWrapper<EventOutbox> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EventOutbox::getStatus, EventOutboxStatus.PENDING.name())
                .lt(EventOutbox::getRetryCount, maxRetries)
                .orderByAsc(EventOutbox::getCreateTime)
                .last("limit " + batchSize);

        // 2. 按创建时间顺序批量捞取事件，控制 batchSize，避免一次扫描拖垮数据库和 MQ。
        List<EventOutbox> events = eventOutboxMapper.selectList(wrapper);

        // 3. 逐条投递。单条事件内部会负责成功标记 SENT，失败累计 retryCount。
        for (EventOutbox event : events) {
            relay(event);
        }
    }

    /**
     * 投递单条 Outbox 事件，并根据投递结果更新事件状态。
     * <p>
     * 这是 Transactional Outbox 的单条消息处理核心：
     * 先把 outbox.payload 反序列化成领域事件，再交给 OrderEventPublisher 投递到 RocketMQ/Redis Stream；
     * 成功则标记 SENT，失败则记录错误和重试次数，超过阈值后标记 FAILED。
     *
     * @param outbox 从 event_outbox 表中扫描出来的待投递事件记录
     */
    private void relay(EventOutbox outbox) {
        try {
            // 1. Outbox 表中存的是 JSON 字符串，这里先还原成真正的领域事件对象。
            // 如果 payload 已损坏或字段不兼容，readValue 会抛异常，事件会进入重试/失败分支。
            OrderCreatedEvent event = objectMapper.readValue(outbox.getPayload(), OrderCreatedEvent.class);

            // 2. 调用统一发布器投递事件。
            // 具体是发 RocketMQ 还是 Redis Stream，由 OrderEventPublisher 根据配置决定。
            orderEventPublisher.publish(event);

            // 3. 只要 publish 没抛异常，就认为本次投递成功，把本地消息状态标记为 SENT。
            // 这样后续 relayPendingEvents 扫描 PENDING 时，就不会重复捞到这条事件。
            outbox.setStatus(EventOutboxStatus.SENT.name());
            outbox.setLastError(null);
        } catch (Exception ex) {
            // 4. 任何异常都不会让 relay 线程整体崩掉，而是记录到 outbox 表里等待下次重试。
            outbox.setRetryCount(outbox.getRetryCount() + 1);
            outbox.setLastError(trimError(ex.getMessage()));

            // 5. 超过最大重试次数后标记为 FAILED，避免坏消息永久占用投递资源。
            // FAILED 事件可以后续通过运维后台、SQL 或补偿任务人工介入处理。
            if (outbox.getRetryCount() >= maxRetries) {
                outbox.setStatus(EventOutboxStatus.FAILED.name());
            }
            log.warn("Failed to relay outbox event {}: {}", outbox.getId(), ex.getMessage());
        }

        // 6. 无论成功还是失败，都把最新状态写回 event_outbox。
        // 成功：PENDING -> SENT；失败：retryCount + 1，并记录 lastError，必要时变成 FAILED。
        eventOutboxMapper.updateById(outbox);
    }

    private String toJson(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize order event", ex);
        }
    }

    private String trimError(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
