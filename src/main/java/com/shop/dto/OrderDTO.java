package com.shop.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class OrderDTO {
    private String id;
    private String orderNo;
    private String userId;
    private BigDecimal totalAmount;
    private Integer totalQuantity;
    private String status;
    private LocalDateTime createTime;
    private List<OrderItemDTO> items;
}
