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

    private static final String CART_KEY_PREFIX = "cart:";
    private static final String CHECKOUT_LOCK_KEY_PREFIX = "lock:checkout:";
    private static final long CART_TTL_HOURS = 24;
    private static final Duration CHECKOUT_LOCK_TTL = Duration.ofSeconds(15);

    public CartService(RedisTemplate<String, Object> redisTemplate,
                       ProductService productService,
                       OrderService orderService,
                       DistributedLockService distributedLockService,
                       CheckoutIdempotencyService checkoutIdempotencyService) {
        this.redisTemplate = redisTemplate;
        this.productService = productService;
        this.orderService = orderService;
        this.distributedLockService = distributedLockService;
        this.checkoutIdempotencyService = checkoutIdempotencyService;
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

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO checkout(String userId, String idempotencyKey) {
        boolean idempotent = checkoutIdempotencyService.isEnabled(idempotencyKey);
        if (idempotent) {
            OrderDTO completedOrder = checkoutIdempotencyService.findCompleted(userId, idempotencyKey).orElse(null);
            if (completedOrder != null) {
                return completedOrder;
            }
            checkoutIdempotencyService.begin(userId, idempotencyKey);
        }

        String lockKey = CHECKOUT_LOCK_KEY_PREFIX + userId;
        String lockToken = UUID.randomUUID().toString();
        if (!distributedLockService.tryLock(lockKey, lockToken, CHECKOUT_LOCK_TTL)) {
            if (idempotent) {
                checkoutIdempotencyService.clear(userId, idempotencyKey);
            }
            throw new IllegalStateException("Checkout is already in progress");
        }

        try {
            OrderDTO orderDTO = doCheckout(userId);
            if (idempotent) {
                checkoutIdempotencyService.complete(userId, idempotencyKey, orderDTO);
            }
            return orderDTO;
        } catch (Exception e) {
            if (idempotent) {
                checkoutIdempotencyService.clear(userId, idempotencyKey);
            }
            throw e;
        } finally {
            distributedLockService.releaseLock(lockKey, lockToken);
        }
    }

    private OrderDTO doCheckout(String userId) {
        Cart cart = getCart(userId);
        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty, cannot checkout.");
        }

        // Snapshot cart items before clearing, so we can restore on failure
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
