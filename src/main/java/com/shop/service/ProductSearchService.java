package com.shop.service;

import com.shop.model.Product;
import com.shop.search.ProductSearchDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class ProductSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final boolean enabled;

    public ProductSearchService(ElasticsearchOperations elasticsearchOperations,
                                @Value("${app.search.elasticsearch.enabled:true}") boolean enabled) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.enabled = enabled;
    }

    public void index(Product product) {
        if (!enabled || product == null) {
            return;
        }
        try {
            elasticsearchOperations.save(toDocument(product));
        } catch (RuntimeException ex) {
            log.warn("Failed to index product {} into Elasticsearch: {}", product.getId(), ex.getMessage());
        }
    }

    public void delete(String productId) {
        if (!enabled || StringUtils.isBlank(productId)) {
            return;
        }
        try {
            elasticsearchOperations.delete(productId, ProductSearchDocument.class);
        } catch (RuntimeException ex) {
            log.warn("Failed to delete product {} from Elasticsearch: {}", productId, ex.getMessage());
        }
    }

    public Optional<List<Product>> search(String keyword, String category) {
        if (!enabled || (StringUtils.isBlank(keyword) && StringUtils.isBlank(category))) {
            return Optional.empty();
        }

        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.bool(bool -> {
                        if (StringUtils.isNotBlank(keyword)) {
                            bool.must(must -> must.multiMatch(multiMatch -> multiMatch
                                    .query(keyword)
                                    .fields("name^3", "description", "category")));
                        }
                        if (StringUtils.isNotBlank(category)) {
                            bool.filter(filter -> filter.term(term -> term
                                    .field("category")
                                    .value(category)));
                        }
                        return bool;
                    }))
                    .build();

            SearchHits<ProductSearchDocument> hits = elasticsearchOperations.search(query, ProductSearchDocument.class);
            return Optional.of(hits.stream()
                    .map(SearchHit::getContent)
                    .map(this::toProduct)
                    .toList());
        } catch (RuntimeException ex) {
            log.warn("Elasticsearch search failed, fallback to MySQL: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void rebuildIndex(List<Product> products) {
        if (!enabled || products == null || products.isEmpty()) {
            return;
        }
        try {
            elasticsearchOperations.save(products.stream()
                    .map(this::toDocument)
                    .toList());
        } catch (RuntimeException ex) {
            log.warn("Failed to rebuild Elasticsearch product index: {}", ex.getMessage());
        }
    }

    private ProductSearchDocument toDocument(Product product) {
        return ProductSearchDocument.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stock(product.getStock())
                .category(product.getCategory())
                .build();
    }

    private Product toProduct(ProductSearchDocument document) {
        return Product.builder()
                .id(document.getId())
                .name(document.getName())
                .description(document.getDescription())
                .price(document.getPrice())
                .stock(document.getStock() == null ? 0 : document.getStock())
                .category(document.getCategory())
                .build();
    }
}
