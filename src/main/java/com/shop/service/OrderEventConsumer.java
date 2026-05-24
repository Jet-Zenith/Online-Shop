package com.shop.service;

import lombok.extern.slf4j.Slf4j;
import com.shop.event.OrderEventProperties;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OrderEventConsumer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final OrderEventProperties properties;
    private volatile boolean groupReady;

    public OrderEventConsumer(RedisTemplate<String, Object> redisTemplate, OrderEventProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Redis Stream 订单事件消费者入口。
     * <p>
     * 当 app.events.backend=redis-stream 时，这个方法会被 Spring 定时调用，
     * 持续从 stream:orders 中拉取订单事件并交给 handleRecord 处理。
     * <p>
     * @Scheduled 的 fixedDelayString 表示：上一轮拉取结束后，等待配置的时间再执行下一轮。
     */
    @Scheduled(fixedDelayString = "${app.events.poll-delay-ms:3000}")
    public void consumeOrderEvents() {
        // 1. 消费开关未开启、当前后端不是 redis-stream、消费者组未准备好时，直接跳过本轮轮询。
        if (!properties.isConsumerEnabled()
                || !"redis-stream".equalsIgnoreCase(properties.getBackend())
                || !ensureConsumerGroup()) {
            return;
        }

        try {
            // 2. 使用 Redis Stream 消费者组读取消息。
            // Consumer.from(group, consumerName)：声明当前消费者属于哪个消费者组，以及当前实例名称。
            // count(10)：每轮最多取 10 条，避免单轮处理时间过长。
            // block(500ms)：没有消息时最多阻塞 500ms，减少空轮询带来的 Redis 压力。
            // lastConsumed()：从当前消费者组尚未消费确认的位置继续读取。
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(properties.getOrderConsumerGroup(), properties.getOrderConsumerName()),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                            .count(10)
                            .block(Duration.ofMillis(500)),
                    StreamOffset.create(properties.getOrderStreamKey(), ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) {
                return;
            }

            // 3. 逐条处理消息。单条消息内部会决定正常 ack，还是失败后进入死信流再 ack。
            for (MapRecord<String, Object, Object> record : records) {
                handleRecord(record);
            }
        } catch (RuntimeException ex) {
            // 4. 顶层兜底：Redis 临时异常或反序列化之外的运行时异常不应打停整个定时任务。
            log.warn("Order event consumer skipped this poll: {}", ex.getMessage());
        }
    }

    /**
     * 处理 Redis Stream 中的一条订单事件。
     * <p>
     * 当前项目里只识别 ORDER_CREATED 事件。真实业务中可以在这里触发积分、通知、履约等异步动作。
     *
     * @param record Redis Stream 中读取到的单条消息记录
     */
    private void handleRecord(MapRecord<String, Object, Object> record) {
        try {
            // 1. Redis Stream 读取出来的是 Map 结构，消息字段都在 record.getValue() 中。
            Map<Object, Object> body = record.getValue();
            String eventType = String.valueOf(body.get("eventType"));
            if (!"ORDER_CREATED".equals(eventType)) {
                // 2. 非订单创建事件暂不处理，但仍会 ack，避免无效消息一直阻塞消费者组。
                log.warn("Ignored unsupported order event type {}", eventType);
            } else {
                // 3. 这里先用日志证明消费链路打通；后续可替换为真实异步业务逻辑。
                log.info("Consumed order event orderNo={}, userId={}, amount={}",
                        body.get("orderNo"), body.get("userId"), body.get("totalAmount"));
            }
            // 4. 处理成功后确认消费。ack 后，该消息不会再被当前消费者组重复投递。
            acknowledge(record.getId());
        } catch (RuntimeException ex) {
            // 5. 单条消息处理失败时，先写入死信流保留现场，再 ack 原消息，避免坏消息反复卡住消费进度。
            publishDeadLetter(record, ex);
            acknowledge(record.getId());
        }
    }

    /**
     * 确保 Redis Stream 消费者组已经存在。
     * <p>
     * Redis Stream 的消费者组类似 RocketMQ 的 consumerGroup：
     * 同一组里的多个消费者会协作消费，同一条消息通常只会分配给组内一个消费者。
     *
     * @return true 表示消费者组可用，本轮可以开始拉取消息
     */
    private boolean ensureConsumerGroup() {
        if (groupReady) {
            return true;
        }
        try {
            // 1. 从 0-0 位置创建消费者组，表示这个组理论上可以从 stream 的最早消息开始消费。
            redisTemplate.opsForStream().createGroup(
                    properties.getOrderStreamKey(),
                    ReadOffset.from("0-0"),
                    properties.getOrderConsumerGroup()
            );
            groupReady = true;
            log.info("Created Redis Stream consumer group {} for {}",
                    properties.getOrderConsumerGroup(), properties.getOrderStreamKey());
            return true;
        } catch (RedisSystemException ex) {
            // 2. BUSYGROUP 表示消费者组已经存在，这不是错误，可以直接认为 groupReady。
            if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) {
                groupReady = true;
                return true;
            }
            // 3. 其他 Redis 异常可能是 stream 不存在、Redis 不可用等，跳过本轮等待下一次重试。
            log.warn("Redis Stream consumer group is not ready: {}", ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            log.warn("Redis Stream consumer group is not ready: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 确认某条 Redis Stream 消息已经被当前消费者组处理完成。
     * <p>
     * ack 之后，这条消息会从消费者组的 pending 列表中移除，后续不会再被重复投递给该组。
     *
     * @param recordId Redis Stream 消息 ID
     */
    private void acknowledge(RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(
                properties.getOrderStreamKey(),
                properties.getOrderConsumerGroup(),
                recordId
        );
    }

    /**
     * 将处理失败的消息写入死信 Stream。
     * <p>
     * 死信流用于保存无法正常消费的消息现场，方便后续排查、人工修复或补偿重放。
     *
     * @param record 原始 Redis Stream 消息
     * @param ex     处理失败时抛出的异常
     */
    private void publishDeadLetter(MapRecord<String, Object, Object> record, RuntimeException ex) {
        // 1. 保留来源 stream、原始 recordId 和错误原因，方便定位是哪条消息消费失败。
        Map<String, Object> deadLetter = new LinkedHashMap<>();
        deadLetter.put("sourceStream", properties.getOrderStreamKey());
        deadLetter.put("sourceRecordId", record.getId().getValue());
        deadLetter.put("error", ex.getMessage());

        // 2. 把原消息体完整复制进死信消息，避免只看到错误但丢失业务上下文。
        record.getValue().forEach((key, value) -> deadLetter.put(String.valueOf(key), value));

        // 3. 写入专门的 dead letter stream，和正常订单事件流隔离。
        redisTemplate.opsForStream().add(properties.getOrderDeadLetterStreamKey(), deadLetter);
        log.warn("Moved order event {} to dead letter stream {}",
                record.getId(), properties.getOrderDeadLetterStreamKey());
    }
}
