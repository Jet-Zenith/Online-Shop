package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.dto.OrderDTO;
import com.shop.dto.OrderItemDTO;
import com.shop.exception.BusinessException;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.model.CartItem;
import com.shop.model.OrderItem;
import com.shop.model.OrderStatus;
import com.shop.model.Product;
import com.shop.model.ShopOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderOutboxService orderOutboxService;

    public OrderService(OrderMapper orderMapper, OrderItemMapper orderItemMapper, OrderOutboxService orderOutboxService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.orderOutboxService = orderOutboxService;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO createOrder(String userId, List<CartItem> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            throw BusinessException.badRequest("Cart is empty, cannot create order");
        }

        List<OrderItem> orderItems = cartItems.stream()
                .map(this::toOrderItem)
                .toList();

        BigDecimal totalAmount = orderItems.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalQuantity = orderItems.stream()
                .mapToInt(OrderItem::getQuantity)
                .sum();

        ShopOrder order = ShopOrder.builder()
                .orderNo(generateOrderNo())
                .userId(userId)
                .totalAmount(totalAmount)
                .totalQuantity(totalQuantity)
                .status(OrderStatus.CREATED.name())
                .build();
        orderMapper.insert(order);

        for (OrderItem item : orderItems) {
            item.setOrderId(order.getId());
            orderItemMapper.insert(item);
        }

        OrderDTO orderDTO = toDTO(order, orderItems);
        orderOutboxService.saveOrderCreatedEvent(orderDTO);
        return orderDTO;
    }

    public Page<OrderDTO> getUserOrders(String userId, int pageNum, int pageSize) {
        LambdaQueryWrapper<ShopOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShopOrder::getUserId, userId)
                .orderByDesc(ShopOrder::getCreateTime);

        Page<ShopOrder> page = orderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<OrderDTO> dtoPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        dtoPage.setRecords(page.getRecords().stream()
                .map(order -> toDTO(order, getOrderItems(order.getId())))
                .toList());
        return dtoPage;
    }

    public OrderDTO getUserOrder(String userId, String orderId) {
        ShopOrder order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw BusinessException.notFound("Order not found");
        }
        return toDTO(order, getOrderItems(orderId));
    }

    private List<OrderItem> getOrderItems(String orderId) {
        LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderItem::getOrderId, orderId);
        return orderItemMapper.selectList(wrapper);
    }

    private OrderItem toOrderItem(CartItem cartItem) {
        Product product = cartItem.getProduct();
        if (product == null || product.getPrice() == null || cartItem.getQuantity() <= 0) {
            throw BusinessException.badRequest("Invalid cart item");
        }
        BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity()));
        return OrderItem.builder()
                .productId(product.getId())
                .productName(product.getName())
                .unitPrice(product.getPrice())
                .quantity(cartItem.getQuantity())
                .subtotal(subtotal)
                .build();
    }

    private OrderDTO toDTO(ShopOrder order, List<OrderItem> items) {
        return OrderDTO.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .totalQuantity(order.getTotalQuantity())
                .status(order.getStatus())
                .createTime(order.getCreateTime())
                .items(items.stream()
                        .map(item -> OrderItemDTO.builder()
                                .productId(item.getProductId())
                                .productName(item.getProductName())
                                .unitPrice(item.getUnitPrice())
                                .quantity(item.getQuantity())
                                .subtotal(item.getSubtotal())
                                .build())
                        .toList())
                .build();
    }

    private String generateOrderNo() {
        return "SO" + LocalDateTime.now().format(ORDER_NO_TIME_FORMAT)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
