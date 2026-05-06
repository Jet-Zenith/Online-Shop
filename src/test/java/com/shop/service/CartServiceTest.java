package com.shop.service;

import com.shop.exception.InsufficientStockException;
import com.shop.model.Cart;
import com.shop.model.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ProductService productService;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private CartService cartService;

    private final Product testProduct = Product.builder()
            .id("prod_001")
            .name("Test Product")
            .price(new BigDecimal("99.99"))
            .stock(10)
            .build();

    @Test
    void checkoutShouldDeductStockAtomically() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Cart cart = new Cart("user_001");
        cart.addItem(testProduct, 2);
        when(valueOperations.get("cart:user_001")).thenReturn(cart);
        when(productService.deductStock("prod_001", 2)).thenReturn(true);

        boolean result = cartService.checkout("user_001");

        assertTrue(result);
        verify(productService).deductStock("prod_001", 2);

        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(valueOperations, atLeastOnce()).set(eq("cart:user_001"), cartCaptor.capture(), eq(24L), eq(TimeUnit.HOURS));
        Cart savedCart = cartCaptor.getAllValues().stream()
                .filter(c -> c.getItems().isEmpty())
                .findFirst().orElse(null);
        assertNotNull(savedCart, "Cart should be saved as empty after checkout");
        assertTrue(savedCart.getItems().isEmpty());
    }

    @Test
    void checkoutShouldThrowWhenStockInsufficient() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Cart cart = new Cart("user_001");
        cart.addItem(testProduct, 5);
        when(valueOperations.get("cart:user_001")).thenReturn(cart);
        when(productService.deductStock("prod_001", 5)).thenReturn(false);

        assertThrows(InsufficientStockException.class, () -> cartService.checkout("user_001"));
    }

    @Test
    void checkoutShouldRestoreCartOnFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Cart cart = new Cart("user_001");
        cart.addItem(testProduct, 2);

        Product productB = Product.builder()
                .id("prod_002")
                .name("Product B")
                .price(new BigDecimal("50.00"))
                .stock(0)
                .build();
        cart.addItem(productB, 1);

        when(valueOperations.get("cart:user_001")).thenReturn(cart);
        when(productService.deductStock("prod_001", 2)).thenReturn(true);
        when(productService.deductStock("prod_002", 1))
                .thenThrow(new InsufficientStockException("Product B out of stock"));

        assertThrows(InsufficientStockException.class, () -> cartService.checkout("user_001"));

        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(valueOperations, atLeastOnce()).set(eq("cart:user_001"), cartCaptor.capture(), eq(24L), eq(TimeUnit.HOURS));
        List<Cart> allCarts = cartCaptor.getAllValues();
        Cart restoredCart = allCarts.get(allCarts.size() - 1);
        assertEquals(2, restoredCart.getItems().size(), "Cart should be restored with original items");
    }

    @Test
    void checkoutShouldThrowWhenCartEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Cart cart = new Cart("user_001");
        when(valueOperations.get("cart:user_001")).thenReturn(cart);

        assertThrows(IllegalStateException.class, () -> cartService.checkout("user_001"));
    }

    @Test
    void addItemShouldValidateStock() {
        when(productService.getProductById("prod_001")).thenReturn(testProduct);

        assertThrows(InsufficientStockException.class,
                () -> cartService.addItemToCart("user_001", "prod_001", 999));

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void mergeCartShouldCombineItems() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Cart existingCart = new Cart("user_001");
        when(valueOperations.get("cart:user_001")).thenReturn(existingCart);

        Cart tempCart = new Cart("temp");
        tempCart.addItem(testProduct, 3);

        boolean result = cartService.mergeCart("user_001", tempCart);

        assertTrue(result);
        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(valueOperations).set(eq("cart:user_001"), cartCaptor.capture(), eq(24L), eq(TimeUnit.HOURS));
        assertEquals(1, cartCaptor.getValue().getItems().size());
        assertEquals(3, cartCaptor.getValue().getItems().get(0).getQuantity());
    }
}
