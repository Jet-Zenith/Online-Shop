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

    /**
     * 将单个商品写入 Elasticsearch 索引
     * <p>
     * 商品新增、修改或库存变化后调用该方法，让搜索索引尽量和 MySQL 主数据保持同步。
     *
     * @param product 需要同步到 ES 的商品实体
     */
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

    /**
     * 从 Elasticsearch 索引中删除商品文档。
     * <p>
     * 商品在 MySQL 中删除后调用该方法，避免用户搜索到已经不存在的商品。
     *
     * @param productId 商品 ID，同时也是 ES 文档 ID
     */
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

    /**
     * 使用 Elasticsearch 搜索商品
     * <p>
     * 返回 Optional.empty() 表示当前不能使用 ES 查询，例如 ES 未启用、条件为空或 ES 查询异常；
     * 调用方可以据此降级到 MySQL 查询。
     *
     * @param keyword  搜索关键字，用于匹配商品名称、描述和分类
     * @param category 商品分类，用于精确过滤
     * @return ES 查询结果；如果无法使用 ES，则返回 Optional.empty()
     */
    public Optional<List<Product>> search(String keyword, String category) {
        if (!enabled || (StringUtils.isBlank(keyword) && StringUtils.isBlank(category))) {
            return Optional.empty();
        }

        try {
            // 构建 bool 查询：must 负责全文相关性匹配，filter 负责结构化精确过滤。
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.bool(bool -> {
                        if (StringUtils.isNotBlank(keyword)) {
                            // multi_match 会同时搜索多个字段；name^3 表示商品名权重更高，命中名称的结果会更靠前。
                            bool.must(must -> must.multiMatch(multiMatch -> multiMatch
                                    .query(keyword)
                                    .fields("name^3", "description", "category")));
                        }
                        if (StringUtils.isNotBlank(category)) {
                            // category 是 Keyword 字段，适合用 term 做精确匹配过滤，不参与相关性评分。
                            bool.filter(filter -> filter.term(term -> term
                                    .field("category")
                                    .value(category)));
                        }
                        return bool;
                    }))
                    .build();

            // 执行 ES 查询后，把搜索文档转换回接口层使用的 Product 对象。
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

    /**
     * 全量重建商品搜索索引。
     * <p>
     * 当 MySQL 与 Elasticsearch 数据不一致，或首次接入 ES 时，可以从 MySQL 读取全量商品后调用该方法重建索引。
     *
     * @param products 从 MySQL 查询出的商品列表
     */
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

    /**
     * 将 MySQL 商品实体转换为 Elasticsearch 搜索文档。
     *
     * @param product MySQL 商品实体
     * @return ES 商品搜索文档
     */
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

    /**
     * 将 Elasticsearch 搜索文档转换为商品实体。
     * <p>
     * 这里返回的 Product 主要用于搜索结果展示，不承担数据库持久化职责。
     *
     * @param document ES 商品搜索文档
     * @return 商品展示对象
     */
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
