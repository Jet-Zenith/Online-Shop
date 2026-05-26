package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.exception.BusinessException;
import com.shop.mapper.ProductMapper;
import com.shop.dto.StockDeductionRequest;
import com.shop.model.Product;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ProductService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductMapper productMapper;
    private final ProductSearchService productSearchService;

    private static final String PRODUCT_KEY_PREFIX = "product:";
    private static final String ALL_PRODUCTS_KEY = "all:products";
    private static final String HOT_PRODUCTS_KEY = "hot:products";
    private static final long PRODUCT_CACHE_TTL_HOURS = 1;
    private static final long HOT_PRODUCTS_TTL_MINUTES = 30;
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    public ProductService(RedisTemplate<String, Object> redisTemplate,
                          ProductMapper productMapper,
                          ProductSearchService productSearchService) {
        this.redisTemplate = redisTemplate;
        this.productMapper = productMapper;
        this.productSearchService = productSearchService;
    }

    public Product createProduct(Product product) {
        normalize(product);
        productMapper.insert(product);
        redisTemplate.opsForValue().set(PRODUCT_KEY_PREFIX + product.getId(), product, PRODUCT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        evictListCaches();
        productSearchService.index(product);
        return product;
    }

    public Product updateProduct(String productId, Product product) {
        if (getProductById(productId) == null) {
            throw BusinessException.notFound("Product not found");
        }
        normalize(product);
        product.setId(productId);
        productMapper.updateById(product);
        redisTemplate.opsForValue().set(PRODUCT_KEY_PREFIX + productId, product, PRODUCT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        evictListCaches();
        productSearchService.index(product);
        return product;
    }

    public boolean deleteProduct(String productId) {
        int rows = productMapper.deleteById(productId);
        if (rows > 0) {
            redisTemplate.delete(PRODUCT_KEY_PREFIX + productId);
            evictListCaches();
            productSearchService.delete(productId);
            return true;
        }
        return false;
    }

    public Product getProductById(String productId) {
        Product product = (Product) redisTemplate.opsForValue().get(PRODUCT_KEY_PREFIX + productId);
        if (product != null) {
            return product;
        }

        product = productMapper.selectById(productId);
        if (product != null) {
            redisTemplate.opsForValue().set(PRODUCT_KEY_PREFIX + productId, product, PRODUCT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        }
        return product;
    }

    public List<Product> getAllProducts() {
        @SuppressWarnings("unchecked")
        List<Product> products = (List<Product>) redisTemplate.opsForValue().get(ALL_PRODUCTS_KEY);
        if (products != null) {
            return products;
        }

        products = productMapper.selectList(null);
        redisTemplate.opsForValue().set(ALL_PRODUCTS_KEY, products, PRODUCT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        return products;
    }

    /**
     * 获取首页热门商品列表
     * 采用经典的 Cache-Aside (旁路缓存) 模式，极大地减轻数据库压力。
     *
     * @return 热门商品列表
     */
    public List<Product> getHotProducts() {
        // 1. 第一防线：查缓存
        // 从 Redis 中尝试获取热门数据。此处高并发下命中率极高。
        @SuppressWarnings("unchecked")
        List<Product> hotProducts = (List<Product>) redisTemplate.opsForValue().get(HOT_PRODUCTS_KEY);

        // 如果缓存命中，直接返回，不再请求数据库 (短路返回)
        if (hotProducts != null) {
            return hotProducts;
        }

        // 2. 第二防线：查数据库 (缓存未命中或已过期)
        // 业务逻辑简易实现：取出库存最少的 5 件商品视为"热门"
        // .last("limit 5") 保证了只会返回 5 条，避免了潜在的 OOM 风险
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(Product::getStock).last("limit 5");
        hotProducts = productMapper.selectList(wrapper);

        // 3. 回写缓存 (极其关键)
        // 将查询到的结果存入 Redis，并设置过期时间（防止数据永久变脏）。
        // ⚠️ 高并发预警：此处存在"缓存击穿"风险。极端情况下缓存失效的瞬间，
        // 大量并发会打穿缓存直奔 MySQL。进阶方案可引入分布式锁 (互斥锁) 处理。
        redisTemplate.opsForValue().set(HOT_PRODUCTS_KEY, hotProducts, HOT_PRODUCTS_TTL_MINUTES, TimeUnit.MINUTES);

        return hotProducts;
    }

    /**
     * 搜索商品列表
     * <p>
     * 查询策略：优先使用 Elasticsearch 做全文检索；如果 ES 未启用、查询异常或不适合处理当前条件，
     * 则自动降级为 MySQL 查询，保证搜索功能具备可用性兜底。
     *
     * @param keyword  搜索关键字，可匹配商品名称和描述
     * @param category 商品分类，可用于精确过滤
     * @return 匹配条件的商品列表
     */
    public List<Product> searchProducts(String keyword, String category) {
        // 1. 优先走 Elasticsearch。
        // ES 更适合全文检索：商品名称、描述可以被分词并按相关性排序，搜索体验比 MySQL LIKE 更好。
        List<Product> elasticsearchResult = productSearchService.search(keyword, category).orElse(null);
        if (elasticsearchResult != null) {
            // 2. 只要 ES 正常返回结果，就直接使用 ES 查询结果。
            // 注意：空列表也是有效结果，表示 ES 查过但没有匹配商品，不需要再回退 MySQL。
            return elasticsearchResult;
        }

        // 3. 如果 ES 未启用、查询条件不适合走 ES，或 ES 查询异常，则降级走 MySQL。
        // 这保证搜索功能不会因为搜索引擎故障而整体不可用。
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.isNotBlank(keyword)) {
            // 4. MySQL 兜底方案：用 LIKE 匹配商品名称和描述。
            // 数据量大时性能不如 ES，但可作为可靠降级路径。
            wrapper.and(i -> i.like(Product::getName, keyword)
                    .or()
                    .like(Product::getDescription, keyword));
        }

        if (StringUtils.isNotBlank(category)) {
            // 5. 分类属于结构化字段，MySQL 中用等值匹配即可。
            wrapper.eq(Product::getCategory, category);
        }

        // 6. 返回 MySQL 兜底查询结果。
        return productMapper.selectList(wrapper);
    }

    public int rebuildSearchIndex() {
        List<Product> products = productMapper.selectList(null);
        productSearchService.rebuildIndex(products);
        return products.size();
    }

    public Page<Product> getProductsByPage(int pageNum, int pageSize) {
        int safePageNum = Math.max(pageNum, DEFAULT_PAGE_NUM);
        int safePageSize = Math.min(Math.max(pageSize, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
        Page<Product> page = new Page<>(safePageNum, safePageSize);
        return productMapper.selectPage(page, null);
    }

    public boolean deductStock(String productId, int quantity) {
        if (StringUtils.isBlank(productId) || quantity <= 0) {
            throw BusinessException.badRequest("Invalid stock deduction request");
        }
        boolean deducted = productMapper.deductStock(productId, quantity) > 0;
        if (deducted) {
            redisTemplate.delete(PRODUCT_KEY_PREFIX + productId);
            evictListCaches();
            Product product = productMapper.selectById(productId);
            if (product != null) {
                productSearchService.index(product);
            }
        }
        return deducted;
    }

    /**
     * 批量扣减商品库存。
     * <p>
     * 该方法用于购物车结算场景：一次订单可能包含多个商品，如果逐个调用 deductStock，
     * 会产生多次数据库往返；这里先合并同一商品的扣减数量，再通过 Mapper 执行批量条件更新。
     * <p>
     * 返回 false 表示至少有一个商品库存不足，调用方应终止结算并触发事务回滚。
     *
     * @param deductions 待扣减的商品库存请求列表
     * @return true 表示所有商品库存扣减成功；false 表示存在库存不足或未成功更新的商品
     */
    public boolean batchDeductStock(List<StockDeductionRequest> deductions) {
        // 1. 请求不能为空，空扣减没有业务意义。
        if (deductions == null || deductions.isEmpty()) {
            throw BusinessException.badRequest("Stock deduction request is required");
        }

        // 2. 合并同一商品的扣减数量。
        // 例如购物车中因异常数据出现两条 prod_001，数量 1 和 2，会合并成 prod_001 -> 3。
        Map<String, Integer> quantityByProductId = new LinkedHashMap<>();
        for (StockDeductionRequest deduction : deductions) {
            if (deduction == null || StringUtils.isBlank(deduction.getProductId()) || deduction.getQuantity() <= 0) {
                throw BusinessException.badRequest("Invalid stock deduction request");
            }
            quantityByProductId.merge(deduction.getProductId(), deduction.getQuantity(), Integer::sum);
        }

        // 3. 将合并后的 Map 重新转换成批量扣减请求列表，交给 Mapper 拼接批量 SQL。
        List<StockDeductionRequest> normalizedDeductions = quantityByProductId.entrySet().stream()
                .map(entry -> new StockDeductionRequest(entry.getKey(), entry.getValue()))
                .toList();

        // 4. 执行批量条件扣减。底层 SQL 会带 stock >= quantity 条件，防止库存被扣成负数。
        int affectedRows = productMapper.batchDeductStock(normalizedDeductions);
        if (affectedRows != normalizedDeductions.size()) {
            // 影响行数少于商品数，说明至少一个商品库存不足或商品不存在，整体结算应失败。
            return false;
        }

        // 5. 扣减成功后清理商品相关缓存，避免后续读到旧库存。
        List<String> productIds = normalizedDeductions.stream()
                .map(StockDeductionRequest::getProductId)
                .toList();
        productIds.forEach(productId -> redisTemplate.delete(PRODUCT_KEY_PREFIX + productId));
        evictListCaches();

        // 6. 查询最新商品数据并重建 ES 索引，使搜索结果中的库存尽量保持同步。
        List<Product> updatedProducts = productMapper.selectBatchIds(productIds);
        productSearchService.rebuildIndex(updatedProducts);
        return true;
    }

    void evictListCaches() {
        redisTemplate.delete(ALL_PRODUCTS_KEY);
        redisTemplate.delete(HOT_PRODUCTS_KEY);
    }

    private void normalize(Product product) {
        if (product == null) {
            throw BusinessException.badRequest("Product payload is required");
        }
        product.setName(StringUtils.trim(product.getName()));
        product.setCategory(StringUtils.trimToNull(product.getCategory()));
        product.setDescription(StringUtils.trimToNull(product.getDescription()));
    }
}
