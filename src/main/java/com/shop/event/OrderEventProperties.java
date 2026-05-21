package com.shop.event;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.events")
public class OrderEventProperties {
    private String backend = "rocketmq";
    private String orderStreamKey = "stream:orders";
    private String orderConsumerGroup = "order-service";
    private String orderConsumerName = "order-service-1";
    private String orderDeadLetterStreamKey = "stream:orders:dlq";
    private boolean consumerEnabled = true;
    private long pollDelayMs = 3000;
    private String orderTopic = "shop-order-events";
    private String orderCreatedTag = "ORDER_CREATED";
    private String rocketmqConsumerGroup = "shop-order-event-consumer";
}
