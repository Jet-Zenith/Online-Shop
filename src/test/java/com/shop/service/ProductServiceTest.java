package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class ProductServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private ProductService productService;

    private final Product testProduct = Product.builder()
            .id("prod_001")
            .name("Test Product")
            .description("A test product")
            .price(new BigDecimal("99.99"))
            .stock(100)
            .category("Electronics")
            .build();

    @Test
    void getProductByIdShouldReturnFromCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:prod_001")).thenReturn(testProduct);

        Product result = productService.getProductById("prod_001");

        assertNotNull(result);
        assertEquals("Test Product", result.getName());
        verify(productMapper, never()).selectById(anyString());
    }

    @Test
    void getProductByIdShouldFallbackToDatabase() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:prod_001")).thenReturn(null);
        when(productMapper.selectById("prod_001")).thenReturn(testProduct);

        Product result = productService.getProductById("prod_001");

        assertNotNull(result);
        verify(productMapper).selectById("prod_001");
        verify(valueOperations).set(eq("product:prod_001"), eq(testProduct), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    void getAllProductsShouldCacheResult() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("all:products")).thenReturn(null);
        when(productMapper.selectList(any())).thenReturn(List.of(testProduct));

        List<Product> result = productService.getAllProducts();

        assertEquals(1, result.size());
        verify(valueOperations).set(eq("all:products"), anyList(), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    void getAllProductsShouldReturnFromCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("all:products")).thenReturn(List.of(testProduct));

        List<Product> result = productService.getAllProducts();

        assertEquals(1, result.size());
        verify(productMapper, never()).selectList(any());
    }

    @Test
    void createProductShouldInsertAndCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(productMapper.insert(testProduct)).thenReturn(1);

        Product result = productService.createProduct(testProduct);

        assertNotNull(result);
        verify(productMapper).insert(testProduct);
        verify(valueOperations).set(eq("product:prod_001"), eq(testProduct), eq(1L), eq(TimeUnit.HOURS));
        verify(redisTemplate).delete("all:products");
        verify(redisTemplate).delete("hot:products");
    }

    @Test
    void deleteProductShouldRemoveFromCache() {
        when(productMapper.deleteById("prod_001")).thenReturn(1);

        boolean result = productService.deleteProduct("prod_001");

        assertTrue(result);
        verify(redisTemplate).delete("product:prod_001");
        verify(redisTemplate).delete("all:products");
        verify(redisTemplate).delete("hot:products");
    }

    @Test
    void deductStockShouldDelegateToMapper() {
        when(productMapper.deductStock("prod_001", 5)).thenReturn(1);

        boolean result = productService.deductStock("prod_001", 5);

        assertTrue(result);
        verify(productMapper).deductStock("prod_001", 5);
        verify(redisTemplate).delete("product:prod_001");
        verify(redisTemplate).delete("all:products");
        verify(redisTemplate).delete("hot:products");
    }

    @Test
    void deductStockShouldReturnFalseWhenNoRowsAffected() {
        when(productMapper.deductStock("prod_001", 999)).thenReturn(0);

        boolean result = productService.deductStock("prod_001", 999);

        assertFalse(result);
    }

    @Test
    void searchProductsShouldBuildLikeQuery() {
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(testProduct));

        List<Product> result = productService.searchProducts("Test", null);

        assertEquals(1, result.size());
        verify(productMapper).selectList(any(LambdaQueryWrapper.class));
    }
}
