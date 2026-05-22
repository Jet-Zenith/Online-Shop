package com.shop.service;

import com.shop.dto.OrderDTO;
import com.shop.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutIdempotencyServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private CheckoutIdempotencyService checkoutIdempotencyService;

    @Test
    void beginShouldCreateProcessingMarkerForFirstRequest() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("idempotency:checkout:user_001:idem-001"),
                eq("PROCESSING"),
                eq(Duration.ofHours(24)))).thenReturn(true);

        Optional<OrderDTO> result = checkoutIdempotencyService.begin("user_001", "idem-001");

        assertTrue(result.isEmpty());
    }

    @Test
    void beginShouldReturnCompletedOrderInsteadOfAllowingDuplicateCheckout() {
        OrderDTO completedOrder = OrderDTO.builder().id("order_001").orderNo("SO001").build();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("idempotency:checkout:user_001:idem-001"),
                eq("PROCESSING"),
                eq(Duration.ofHours(24)))).thenReturn(false);
        when(valueOperations.get("idempotency:checkout:user_001:idem-001")).thenReturn(completedOrder);

        Optional<OrderDTO> result = checkoutIdempotencyService.begin("user_001", "idem-001");

        assertTrue(result.isPresent());
        assertEquals("order_001", result.get().getId());
    }

    @Test
    void beginShouldRejectWhenAnotherRequestIsStillProcessing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("idempotency:checkout:user_001:idem-001"),
                eq("PROCESSING"),
                eq(Duration.ofHours(24)))).thenReturn(false);
        when(valueOperations.get("idempotency:checkout:user_001:idem-001")).thenReturn("PROCESSING");

        assertThrows(BusinessException.class, () -> checkoutIdempotencyService.begin("user_001", "idem-001"));
    }
}
