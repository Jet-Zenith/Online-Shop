package com.shop.service;

import com.shop.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.events", name = "backend", havingValue = "rocketmq", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${app.events.order-topic:shop-order-events}",
        consumerGroup = "${app.events.rocketmq-consumer-group:shop-order-event-consumer}",
        selectorExpression = "${app.events.order-created-tag:ORDER_CREATED}"
)
public class RocketMqOrderEventConsumer implements RocketMQListener<OrderCreatedEvent> {

    @Override
    public void onMessage(OrderCreatedEvent event) {
        log.info("Consumed RocketMQ order event eventId={}, orderNo={}, userId={}, amount={}",
                event.getEventId(), event.getOrderNo(), event.getUserId(), event.getTotalAmount());
    }
}
