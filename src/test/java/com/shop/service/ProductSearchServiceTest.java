package com.shop.service;

import com.shop.model.Product;
import com.shop.search.ProductSearchDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ProductSearchServiceTest {

    @Test
    void disabledSearchShouldNotCallElasticsearch() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        ProductSearchService service = new ProductSearchService(operations, false);

        service.index(Product.builder()
                .id("prod_001")
                .name("Redis Mug")
                .price(new BigDecimal("39.90"))
                .stock(10)
                .build());
        Optional<java.util.List<Product>> result = service.search("Redis", null);

        assertTrue(result.isEmpty());
        verify(operations, never()).save(any(ProductSearchDocument.class));
    }

    @Test
    void disabledRebuildShouldReturnWithoutCallingElasticsearch() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        ProductSearchService service = new ProductSearchService(operations, false);

        service.rebuildIndex(java.util.List.of(Product.builder().id("prod_001").build()));

        verify(operations, never()).save(any(Iterable.class));
    }
}
