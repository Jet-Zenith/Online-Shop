package com.shop.controller;

import com.shop.common.CurrentUser;
import com.shop.common.Result;
import com.shop.dto.CartRequest;
import com.shop.dto.OrderDTO;
import com.shop.model.Cart;
import com.shop.model.User;
import com.shop.service.CartService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public Result<Cart> getCart(@CurrentUser User user) {
        return Result.success(cartService.getCart(user.getId()));
    }

    @PostMapping("/items")
    public Result<Cart> addItemToCart(@CurrentUser User user, @Valid @RequestBody CartRequest cartRequest) {
        Cart cart = cartService.addItemToCart(user.getId(), cartRequest.getProductId(), cartRequest.getQuantity());
        return Result.success(cart);
    }

    @DeleteMapping("/items/{productId}")
    public Result<Cart> removeItemFromCart(@CurrentUser User user, @PathVariable String productId) {
        return Result.success(cartService.removeItemFromCart(user.getId(), productId));
    }

    @PutMapping("/items/{productId}")
    public Result<Cart> updateItemQuantity(
            @CurrentUser User user,
            @PathVariable String productId,
            @RequestParam @Min(0) @Max(100) int quantity) {
        return Result.success(cartService.updateItemQuantity(user.getId(), productId, quantity));
    }

    @DeleteMapping
    public Result<Cart> clearCart(@CurrentUser User user) {
        return Result.success(cartService.clearCart(user.getId()));
    }

    @PostMapping("/merge")
    public Result<Cart> mergeCart(@CurrentUser User user, @RequestBody Cart temporaryCart) {
        cartService.mergeCart(user.getId(), temporaryCart);
        return Result.success(cartService.getCart(user.getId()));
    }

    /**
     * 购物车结算下单
     *
     * @param user           当前登录用户（由全局拦截器自动注入）
     * @param idempotencyKey 幂等性防重令牌。
     * 由前端在进入结算页时生成并放入 Header。
     * 核心目的：防止因网络超时重试、用户疯狂连点导致的“重复扣款、重复下单”灾难。
     * @return 订单详情 DTO
     */
    @PostMapping("/checkout")
    public Result<OrderDTO> checkout(
            @CurrentUser User user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // 将真正的硬核逻辑（防重校验、库存扣减、分布式事务）全部下沉到 Service 层
        return Result.success(cartService.checkout(user.getId(), idempotencyKey));
    }
}
