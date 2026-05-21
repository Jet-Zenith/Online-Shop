package com.shop.event;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class OrderCreatedEvent {
    private String eventId;
    private String eventType;
    private String orderId;
    private String orderNo;
    private String userId;
    private BigDecimal totalAmount;
    private Integer totalQuantity;
    private String status;
    private LocalDateTime occurredAt;
    private String traceId;
}
