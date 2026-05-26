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
     * 定时从 Redis Stream 拉取订单事件。
     * <p>
     * 这条链路只在 app.events.backend=redis-stream 时生效；
     * RocketMQ 模式下由 RocketMqOrderEventConsumer 消费订单事件。
     * `@Scheduled` 会让 Spring 周期性调用该方法，默认上一轮结束 3000ms 后再执行下一轮。
     */
    @Scheduled(fixedDelayString = "${app.events.poll-delay-ms:3000}")
    public void consumeOrderEvents() {
        // 消费开关关闭 -> 不消费
        // 当前不是 redis-stream 模式 -> 不消费
        // 消费者组没准备好 -> 不消费
        if (!properties.isConsumerEnabled()
                || !"redis-stream".equalsIgnoreCase(properties.getBackend())
                || !ensureConsumerGroup()) {
            return;
        }

        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(// 从 Redis Stream 消费者组读取消息
                    Consumer.from(properties.getOrderConsumerGroup(), properties.getOrderConsumerName()),// 指定消费者组和当前消费者实例名
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                            .count(10)//每轮最多拉 10 条
                            .block(Duration.ofMillis(500)),// 没有消息时最多等待 500ms，减少空轮询
                    StreamOffset.create(properties.getOrderStreamKey(), ReadOffset.lastConsumed())// - lastConsumed()：读取当前消费者组尚未确认的新消息
            );

            if (records == null || records.isEmpty()) {
                return;
            }

            // 每条消息交给 handleRecord 处理
            for (MapRecord<String, Object, Object> record : records) {
                handleRecord(record);
            }
        } catch (RuntimeException ex) {
            // 顶层兜底：Redis 临时异常不能打停整个定时任务，记录日志后等待下一轮调度重试。
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
                // 3. 这里先用日志证明消费链路打通；后续可替换为真实异步业务逻辑，如发通知、加积分、发优惠券等
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
     * 确保 read(...) 之前 Redis Stream 消费者组已经存在。
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
            redisTemplate.opsForStream().createGroup(//在 stream:orders 上创建一个消费者组
                    properties.getOrderStreamKey(),
                    ReadOffset.from("0-0"),//从 0-0 开始消费
                    properties.getOrderConsumerGroup()//消费者组名来自配置
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
     * 这一步对应 Redis Stream 的 XACK 命令。
     * 消息被消费者组读取后，会进入该消费者组的 pending 列表；
     * 只有 ack 后，Redis 才认为这条消息已经处理完毕，并将它从 pending 列表中移除。
     * <p>
     * 注意：处理失败的消息在写入死信流后也会 ack 原消息，
     * 这样可以避免坏消息反复阻塞正常消费进度。
     *
     * @param recordId Redis Stream 消息 ID
     */
    private void acknowledge(RecordId recordId) {
        // 对指定 stream + consumer group + recordId 执行确认消费。
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

        // 2. 把原消息体完整复制进死信消息
        record.getValue().forEach((key, value) -> deadLetter.put(String.valueOf(key), value));

        // 3. 写入专门的 dead letter stream，和正常订单事件流隔离。
        redisTemplate.opsForStream().add(properties.getOrderDeadLetterStreamKey(), deadLetter);
        log.warn("Moved order event {} to dead letter stream {}",
                record.getId(), properties.getOrderDeadLetterStreamKey());
    }
}
