package com.shop.service;

import com.shop.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
// 只有当 app.events.backend=rocketmq 时才注册这个消费者。
// 如果切换成 redis-stream，就由 OrderEventConsumer 走 Redis Stream 消费链路，避免两个消费者同时处理同一类事件。
@ConditionalOnProperty(prefix = "app.events", name = "backend", havingValue = "rocketmq", matchIfMissing = true)
// RocketMQ 消费者声明：
// topic：订阅哪个主题，这里默认订阅订单事件主题 shop-order-events。
// consumerGroup：消费者组名称，同一组内多个实例会负载均衡消费，避免同一条消息被同组重复处理。
// selectorExpression：Tag 过滤条件，这里只消费 ORDER_CREATED 类型的订单创建事件。
@RocketMQMessageListener(
        topic = "${app.events.order-topic:shop-order-events}",
        consumerGroup = "${app.events.rocketmq-consumer-group:shop-order-event-consumer}",
        selectorExpression = "${app.events.order-created-tag:ORDER_CREATED}"
)
public class RocketMqOrderEventConsumer implements RocketMQListener<OrderCreatedEvent> {

    /**
     * RocketMQ 收到匹配 topic/tag 的消息后，会自动反序列化为 OrderCreatedEvent 并回调这个方法。
     * <p>
     * 真实企业项目里，这里通常会继续触发积分、优惠券、通知、履约、数据统计等异步业务。
     * 当前项目先记录日志，作为订单事件消费链路已经打通的可观测证明。
     *
     * @param event RocketMQ 推送过来的订单创建事件
     */
    @Override
    public void onMessage(OrderCreatedEvent event) {
        /*
        目前 onMessage 只是打印日志，证明订单事件已经被 RocketMQ 消费到了。
        在真实业务里，这里通常会扩展成：
            给用户发下单成功通知
            增加用户积分
            触发优惠券核销
            推送订单到履约系统
            写订单统计数据
            通知仓储系统准备发货
        */
        // 消费者侧也打印 eventId/orderNo，方便和 Outbox 表、RocketMQ 控制台、服务日志做链路排查。
        log.info("Consumed RocketMQ order event eventId={}, orderNo={}, userId={}, amount={}",
                event.getEventId(), event.getOrderNo(), event.getUserId(), event.getTotalAmount());
    }
}
