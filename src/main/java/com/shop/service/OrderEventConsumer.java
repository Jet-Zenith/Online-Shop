package com.shop.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final String streamKey;
    private final String consumerGroup;
    private final String consumerName;
    private final String deadLetterStreamKey;
    private final boolean enabled;
    private volatile boolean groupReady;

    public OrderEventConsumer(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${app.events.order-stream-key:stream:orders}") String streamKey,
            @Value("${app.events.order-consumer-group:order-service}") String consumerGroup,
            @Value("${app.events.order-consumer-name:order-service-1}") String consumerName,
            @Value("${app.events.order-dead-letter-stream-key:stream:orders:dlq}") String deadLetterStreamKey,
            @Value("${app.events.consumer-enabled:true}") boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        this.deadLetterStreamKey = deadLetterStreamKey;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.events.poll-delay-ms:3000}")
    public void consumeOrderEvents() {
        if (!enabled || !ensureConsumerGroup()) {
            return;
        }

        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                            .count(10)
                            .block(Duration.ofMillis(500)),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) {
                return;
            }

            for (MapRecord<String, Object, Object> record : records) {
                handleRecord(record);
            }
        } catch (RuntimeException ex) {
            log.warn("Order event consumer skipped this poll: {}", ex.getMessage());
        }
    }

    private void handleRecord(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> body = record.getValue();
            String eventType = String.valueOf(body.get("eventType"));
            if (!"ORDER_CREATED".equals(eventType)) {
                log.warn("Ignored unsupported order event type {}", eventType);
            } else {
                log.info("Consumed order event orderNo={}, userId={}, amount={}",
                        body.get("orderNo"), body.get("userId"), body.get("totalAmount"));
            }
            acknowledge(record.getId());
        } catch (RuntimeException ex) {
            publishDeadLetter(record, ex);
            acknowledge(record.getId());
        }
    }

    private boolean ensureConsumerGroup() {
        if (groupReady) {
            return true;
        }
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), consumerGroup);
            groupReady = true;
            log.info("Created Redis Stream consumer group {} for {}", consumerGroup, streamKey);
            return true;
        } catch (RedisSystemException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) {
                groupReady = true;
                return true;
            }
            log.warn("Redis Stream consumer group is not ready: {}", ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            log.warn("Redis Stream consumer group is not ready: {}", ex.getMessage());
            return false;
        }
    }

    private void acknowledge(RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, recordId);
    }

    private void publishDeadLetter(MapRecord<String, Object, Object> record, RuntimeException ex) {
        Map<String, Object> deadLetter = new LinkedHashMap<>();
        deadLetter.put("sourceStream", streamKey);
        deadLetter.put("sourceRecordId", record.getId().getValue());
        deadLetter.put("error", ex.getMessage());
        record.getValue().forEach((key, value) -> deadLetter.put(String.valueOf(key), value));
        redisTemplate.opsForStream().add(deadLetterStreamKey, deadLetter);
        log.warn("Moved order event {} to dead letter stream {}", record.getId(), deadLetterStreamKey);
    }
}
