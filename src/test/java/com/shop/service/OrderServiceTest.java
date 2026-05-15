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

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private OrderEventPublisher orderEventPublisher;

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
        verify(orderEventPublisher).publishOrderCreated(result);
    }
}
