package com.shop.service;

import com.shop.exception.InsufficientStockException;
import com.shop.exception.ProductNotFoundException;
import com.shop.dto.OrderDTO;
import com.shop.model.Cart;
import com.shop.model.CartItem;
import com.shop.model.Product;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        // 1. 【防重防线】检查是否启用了幂等性保护
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

    private OrderDTO doCheckout(String userId) {
        Cart cart = getCart(userId);
        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty, cannot checkout.");
        }

        List<CartItem> itemsSnapshot = new ArrayList<>(cart.getItems());

        // Clear cart from Redis first — if this fails, we abort before touching stock
        clearCart(userId);

        try {
            for (CartItem item : itemsSnapshot) {
                boolean success = productService.deductStock(item.getProduct().getId(), item.getQuantity());
                if (!success) {
                    throw new InsufficientStockException(
                            "Product " + item.getProduct().getName() + " is out of stock");
                }
                inventoryAlertService.checkLowStock(item.getProduct().getId());
            }
            return orderService.createOrder(userId, itemsSnapshot);
        } catch (Exception e) {
            // Restore cart to Redis on failure
            cart.setItems(itemsSnapshot);
            saveCartToRedis(CART_KEY_PREFIX + userId, cart);
            throw e;
        }
    }

    private void saveCartToRedis(String key, Cart cart) {
        redisTemplate.opsForValue().set(key, cart, CART_TTL_HOURS, TimeUnit.HOURS);
    }
}
