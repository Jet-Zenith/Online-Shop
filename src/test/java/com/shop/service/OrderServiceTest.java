package com.shop.service;

import com.shop.dto.OrderDTO;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.model.CartItem;
import com.shop.model.OrderItem;
import com.shop.model.Product;
import com.shop.model.ShopOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private OrderOutboxService orderOutboxService;

    @InjectMocks
    private OrderService orderService;

    @Test
    void createOrderShouldPersistOrderAndItemsWithSnapshotPrice() {
        Product product = Product.builder()
                .id("prod_001")
                .name("Redis Mug")
                .price(new BigDecimal("39.90"))
                .build();
        CartItem cartItem = new CartItem(product, 2);

        doAnswer(invocation -> {
            ShopOrder order = invocation.getArgument(0);
            order.setId("order_001");
            return 1;
        }).when(orderMapper).insert(any(ShopOrder.class));

        OrderDTO result = orderService.createOrder("user_001", List.of(cartItem));

        assertEquals("order_001", result.getId());
        assertEquals(new BigDecimal("79.80"), result.getTotalAmount());
        assertEquals(2, result.getTotalQuantity());
        assertEquals(1, result.getItems().size());

        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(itemCaptor.capture());
        assertEquals("order_001", itemCaptor.getValue().getOrderId());
        assertEquals("Redis Mug", itemCaptor.getValue().getProductName());
        assertEquals(new BigDecimal("39.90"), itemCaptor.getValue().getUnitPrice());
        assertNotNull(result.getOrderNo());
        verify(orderOutboxService).saveOrderCreatedEvent(result);
    }

    @Test
    void getUserOrdersShouldBatchLoadItemsToAvoidNPlusOneQueries() {
        ShopOrder orderA = ShopOrder.builder()
                .id("order_001")
                .orderNo("SO001")
                .userId("user_001")
                .totalAmount(new BigDecimal("39.90"))
                .totalQuantity(1)
                .status("CREATED")
                .build();
        ShopOrder orderB = ShopOrder.builder()
                .id("order_002")
                .orderNo("SO002")
                .userId("user_001")
                .totalAmount(new BigDecimal("19.90"))
                .totalQuantity(1)
                .status("CREATED")
                .build();

        Page<ShopOrder> page = new Page<>(1, 10, 2);
        page.setRecords(List.of(orderA, orderB));

        OrderItem itemA = OrderItem.builder()
                .orderId("order_001")
                .productId("prod_001")
                .productName("Redis Mug")
                .unitPrice(new BigDecimal("39.90"))
                .quantity(1)
                .subtotal(new BigDecimal("39.90"))
                .build();
        OrderItem itemB = OrderItem.builder()
                .orderId("order_002")
                .productId("prod_002")
                .productName("Java Book")
                .unitPrice(new BigDecimal("19.90"))
                .quantity(1)
                .subtotal(new BigDecimal("19.90"))
                .build();

        when(orderMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(itemA, itemB));

        Page<OrderDTO> result = orderService.getUserOrders("user_001", 1, 10);

        assertEquals(2, result.getRecords().size());
        assertEquals(1, result.getRecords().get(0).getItems().size());
        assertEquals(1, result.getRecords().get(1).getItems().size());
        verify(orderItemMapper, times(1)).selectList(any());
    }
}
