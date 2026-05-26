package com.shop.service;

import com.shop.exception.InsufficientStockException;
import com.shop.exception.ProductNotFoundException;
import com.shop.dto.OrderDTO;
import com.shop.dto.StockDeductionRequest;
import com.shop.model.Cart;
import com.shop.model.CartItem;
import com.shop.model.Product;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class CartService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductService productService;
    private final OrderService orderService;
    private final DistributedLockService distributedLockService;
    private final CheckoutIdempotencyService checkoutIdempotencyService;
    private final InventoryAlertService inventoryAlertService;

    private static final String CART_KEY_PREFIX = "cart:";
    private static final String CHECKOUT_LOCK_KEY_PREFIX = "lock:checkout:";
    private static final long CART_TTL_HOURS = 24;
    private static final Duration CHECKOUT_LOCK_TTL = Duration.ofSeconds(15);

    public CartService(RedisTemplate<String, Object> redisTemplate,
                       ProductService productService,
                       OrderService orderService,
                       DistributedLockService distributedLockService,
                       CheckoutIdempotencyService checkoutIdempotencyService,
                       InventoryAlertService inventoryAlertService) {
        this.redisTemplate = redisTemplate;
        this.productService = productService;
        this.orderService = orderService;
        this.distributedLockService = distributedLockService;
        this.checkoutIdempotencyService = checkoutIdempotencyService;
        this.inventoryAlertService = inventoryAlertService;
    }

    public Cart getCart(String userId) {
        String cartKey = CART_KEY_PREFIX + userId;
        Cart cart = (Cart) redisTemplate.opsForValue().get(cartKey);

        if (cart == null) {
            cart = new Cart(userId);
            saveCartToRedis(cartKey, cart);
        }

        return cart;
    }

    public Cart addItemToCart(String userId, String productId, int quantity) {
        Product product = productService.getProductById(productId);
        if (product == null) {
            throw new ProductNotFoundException("Product with ID " + productId + " not found");
        }

        if (product.getStock() < quantity) {
            throw new InsufficientStockException("Insufficient stock for product: " + product.getName());
        }

        Cart cart = getCart(userId);
        int currentQuantity = cart.getItems().stream()
                .filter(item -> item.getProduct() != null && productId.equals(item.getProduct().getId()))
                .mapToInt(CartItem::getQuantity)
                .findFirst()
                .orElse(0);
        if (product.getStock() < currentQuantity + quantity) {
            throw new InsufficientStockException("Insufficient stock for product: " + product.getName());
        }
        cart.addItem(product, quantity);
        saveCartToRedis(CART_KEY_PREFIX + userId, cart);

        return cart;
    }

    public Cart removeItemFromCart(String userId, String productId) {
        Cart cart = getCart(userId);
        cart.removeItem(productId);
        saveCartToRedis(CART_KEY_PREFIX + userId, cart);
        return cart;
    }

    public Cart updateItemQuantity(String userId, String productId, int quantity) {
        if (quantity <= 0) {
            return removeItemFromCart(userId, productId);
        }

        Product product = productService.getProductById(productId);
        if (product == null) {
            throw new ProductNotFoundException("Product with ID " + productId + " not found");
        }
        if (product.getStock() < quantity) {
            throw new InsufficientStockException("Insufficient stock for product: " + product.getName());
        }

        Cart cart = getCart(userId);
        cart.updateItemQuantity(productId, quantity);
        saveCartToRedis(CART_KEY_PREFIX + userId, cart);

        return cart;
    }

    public Cart clearCart(String userId) {
        Cart cart = getCart(userId);
        cart.clearCart();
        saveCartToRedis(CART_KEY_PREFIX + userId, cart);
        return cart;
    }

    public void evictCartCache(String userId) {
        redisTemplate.delete(CART_KEY_PREFIX + userId);
    }

    public boolean mergeCart(String userId, Cart temporaryCart) {
        if (temporaryCart == null || temporaryCart.getItems().isEmpty()) {
            return true;
        }

        Cart userCart = getCart(userId);
        for (CartItem item : temporaryCart.getItems()) {
            userCart.addItem(item.getProduct(), item.getQuantity());
        }

        saveCartToRedis(CART_KEY_PREFIX + userId, userCart);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO checkout(String userId) {
        return checkout(userId, null);
    }

    /**
     * 购物车结算下单
     * 融合了分布式锁、幂等性校验与声明式事务，确保高并发下的绝对数据安全与防超卖。
     *
     * @param userId         当前下单用户
     * @param idempotencyKey 幂等性防重令牌（由前端生成，防止网络重试/连点）
     * @return 创建成功的订单明细
     */
    @Transactional(rollbackFor = Exception.class)// 保证整个下单过程的原子性，任何异常全部回滚
    public OrderDTO checkout(String userId, String idempotencyKey) {
        // 1. 检查是否启用了幂等性保护
        boolean idempotent = checkoutIdempotencyService.isEnabled(idempotencyKey);
        if (idempotent) {
            // 尝试去 Redis 中查找该令牌是否已经有对应的成功订单
            OrderDTO completedOrder = checkoutIdempotencyService.findCompleted(userId, idempotencyKey).orElse(null);
            if (completedOrder != null) {
                return completedOrder;// 命中幂等，直接返回历史结果，截断业务
            }
            // 未命中，在 Redis 中将该令牌标记为“处理中 (IN_PROGRESS)”
            completedOrder = checkoutIdempotencyService.begin(userId, idempotencyKey).orElse(null);
            if (completedOrder != null) {
                return completedOrder;// 二次确认命中幂等，阻断 findCompleted 与 begin 之间的并发穿透
            }
        }

        // 2. 构建用户级别的分布式锁
        String lockKey = CHECKOUT_LOCK_KEY_PREFIX + userId;
        // 生成全局唯一的锁标识，用于后续释放锁时核对身份，防止误删他人的锁
        String lockToken = UUID.randomUUID().toString();
        // 尝试获取分布式锁（非阻塞，拿不到直接抛异常返回）
        if (!distributedLockService.tryLock(lockKey, lockToken, CHECKOUT_LOCK_TTL)) {
            if (idempotent) {
                checkoutIdempotencyService.clear(userId, idempotencyKey);// 拿锁失败，清理占用的幂等状态
            }
            throw new IllegalStateException("Checkout is already in progress");// 友好的并发拦截提示
        }

        try {
            // 3. 【核心业务】执行真正的落库与扣减逻辑
            OrderDTO orderDTO = doCheckout(userId);

            // 4. 下单成功，将订单结果与幂等令牌绑定，存入 Redis
            if (idempotent) {
                checkoutIdempotencyService.complete(userId, idempotencyKey, orderDTO);
            }
            return orderDTO;
        } catch (Exception e) {
            // 5. 异常兜底：业务失败，必须清理幂等状态，允许用户重试
            if (idempotent) {
                checkoutIdempotencyService.clear(userId, idempotencyKey);
            }
            throw e;// 继续向上抛出异常，触发 Spring 的 @Transactional 回滚机制
        } finally {
            // 6. 终极清理：无论成功失败，确保分布式锁被安全释放
            distributedLockService.releaseLock(lockKey, lockToken);
        }
    }

    /**
     * 执行核心结算逻辑（负责购物车处理、库存扣减与订单生成）
     *
     * @param userId 结算用户 ID
     * @return 生成的最终订单对象
     */
    private OrderDTO doCheckout(String userId) {
        Cart cart = getCart(userId);
        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty, cannot checkout.");
        }

        // 1. 生成内存级快照，防止后续并发修改导致 ConcurrentModificationException
        List<CartItem> itemsSnapshot = new ArrayList<>(cart.getItems());

        // 2. 核心架构设计：Fail-Fast 与乐观前置
        // 优先清空 Redis 购物车。若 Redis 异常，可直接阻断流程，保护下游 MySQL 数据库免受无效连接冲击。
        clearCart(userId);

        try {
            // 3. 批量扣减库存
            List<StockDeductionRequest> stockDeductions = itemsSnapshot.stream()
                    .map(item -> new StockDeductionRequest(item.getProduct().getId(), item.getQuantity()))
                    .toList();
            boolean stockDeducted = productService.batchDeductStock(stockDeductions);
            if (!stockDeducted) {
                // 库存不足抛出异常，将触发外层 @Transactional 执行 MySQL 回滚，恢复已扣减的其他商品库存
                throw new InsufficientStockException("Some products are out of stock");
            }

            // 4. 所有商品扣库存成功，落盘创建订单
            OrderDTO orderDTO = orderService.createOrder(userId, itemsSnapshot);

            // 当前已改为事务提交后触发，避免扣库存或创建订单回滚后，已发出的低库存告警无法撤销。
            registerLowStockChecksAfterCommit(itemsSnapshot);
            return orderDTO;

        } catch (Exception e) {
            // 5. 极端场景的手动补偿机制
            // 当底层数据库事务回滚时，Redis 不会自动回滚，必须利用内存快照手动将商品重新装回购物车。
            cart.setItems(itemsSnapshot);
            saveCartToRedis(CART_KEY_PREFIX + userId, cart);

            // 继续向上抛出异常，确保外层声明式事务正确捕获并执行 MySQL 事务回滚
            throw e;
        }
    }

    /**
     * 注册低库存检查任务，并尽量延迟到数据库事务提交后执行。
     * <p>
     * 低库存检查可能会触发日志、告警、消息通知等外部副作用；
     * 如果放在事务内部执行，后续订单创建失败导致事务回滚时，已经发出去的告警无法自动撤销。
     *
     * @param itemsSnapshot 本次结算的购物车商品快照
     */
    private void registerLowStockChecksAfterCommit(List<CartItem> itemsSnapshot) {
        // 1. 它不是马上执行，而是先定义一个“待会儿要执行的任务”。
        Runnable lowStockCheck = () -> itemsSnapshot.stream()
                .map(CartItem::getProduct)//从购物车快照里取商品
                .filter(product -> product != null && product.getId() != null)//过滤掉空商品
                .map(Product::getId)//取商品 ID
                .distinct()//去重
                .forEach(inventoryAlertService::checkLowStock);//逐个调用 inventoryAlertService.checkLowStock(productId)

        // 2. 如果当前没有 Spring 事务，那就没必要等事务提交，直接执行低库存检查。
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            lowStockCheck.run();
            return;
        }

        // 3. 当前存在事务时，注册 afterCommit 回调。
        // 只有订单和库存扣减真正提交成功后，才执行低库存检查，避免回滚后误告警。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lowStockCheck.run();
            }
        });
    }

    private void saveCartToRedis(String key, Cart cart) {
        redisTemplate.opsForValue().set(key, cart, CART_TTL_HOURS, TimeUnit.HOURS);
    }
}
