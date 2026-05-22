package com.shop.service;

import com.shop.dto.OrderDTO;
import com.shop.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class CheckoutIdempotencyService {

    private static final String KEY_PREFIX = "idempotency:checkout:";
    private static final String PROCESSING = "PROCESSING";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;

    public CheckoutIdempotencyService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 判断当前请求是否启用幂等性保护机制
     * 架构意图与工程价值：
     * 1. 兼容降级开关：允许未携带防重 Header 的老版本前端正常下单（不阻断业务），实现新老系统平滑过渡。
     * 2. 遵循开闭原则 (OCP)：将校验逻辑抽离为独立方法统一收口。未来若需增加黑白名单、正则校验或特定 VIP 规则，
     * 只需在此处扩展，绝不侵入并污染极度脆弱的 checkout 核心交易链路。
     *
     * @param idempotencyKey 前端请求头中携带的防重令牌
     * @return true: 令牌有效，开启幂等装甲；false: 令牌缺失或无效（包含全空格等脏数据），走降级裸奔模式
     */
    public boolean isEnabled(String idempotencyKey) {
        // 使用 StringUtils 一次性排查 null、"" (空串) 以及 "   " (纯空格) 三种无效的脏数据
        return StringUtils.isNotBlank(idempotencyKey);
    }

    /**
     * 根据防重令牌，去 Redis 中查询是否已经存在处理成功的历史订单
     *
     * @param userId         当前下单用户 ID
     * @param idempotencyKey 前端传入的防重令牌
     * @return 若已存在成功订单，则将其包裹在 Optional 中返回；否则返回 Optional.empty()
     */
    public Optional<OrderDTO> findCompleted(String userId, String idempotencyKey) {
        // 1. 根据约定的前缀和 token 组装 Redis Key 并获取值
        Object value = redisTemplate.opsForValue().get(buildKey(userId, idempotencyKey));

        // 2. 核心防御：模式匹配 (Pattern Matching)
        // 自动完成判空 (null 拦截) + 类型匹配 (排除 "处理中" 等非实体状态) + 变量强转赋值
        if (value instanceof OrderDTO orderDTO) {
            return Optional.of(orderDTO); // 命中已完成订单，安全返回
        }

        // 3. 未找到或状态未完成，返回规范的空 Optional
        return Optional.empty();
    }

    /**
     * 开启幂等性处理事务，标记当前令牌为“处理中”
     * 核心防御：防止高并发下的极短时间内的 Race Condition（竞态条件）穿透
     *
     * @param userId         用户ID
     * @param idempotencyKey 防重令牌
     * @return 如果并发请求已经抢先完成订单，则返回该历史订单；否则返回 Optional.empty()
     */
    public Optional<OrderDTO> begin(String userId, String idempotencyKey) {
        String key = buildKey(userId, idempotencyKey);

        // 1. 【核心防御】原子操作 SETNX (Set if Not eXists)
        // 尝试将 Key 设置为 PROCESSING。如果成功，说明当前线程是全网第一个拿到该令牌的，安全放行。
        Boolean created = redisTemplate.opsForValue().setIfAbsent(key, PROCESSING, TTL);
        if (Boolean.TRUE.equals(created)) {
            return Optional.empty();
        }

        // 2. 如果 SETNX 失败，说明 Key 已经存在。接下来查明原因：
        Object value = redisTemplate.opsForValue().get(key);

        // 3. 【⚠️ 生产级并发 Bug 预警】
        // 如果查出来是 OrderDTO，说明有极端的并发线程抢先完成了订单（比如当前线程之前被 JVM 停顿了）。
        // 现存代码：直接 return，会导致外层继续执行 doCheckout，引发重复下单！
        // 修复策略：绝对不能 return void 放行。这里直接返回历史订单，由外层立刻截断下单链路。
        if (value instanceof OrderDTO orderDTO) {
            // FIXME: 此处曾存在并发穿透漏洞，必须返回历史订单给外层，不能放行到 doCheckout。
            return Optional.of(orderDTO);
        }

        // 4. 正常拦截：如果状态是 PROCESSING，说明另一个线程正在努力下单，直接打回当前请求。
        throw BusinessException.conflict("Checkout request is already processing");
    }

    public void complete(String userId, String idempotencyKey, OrderDTO orderDTO) {
        redisTemplate.opsForValue().set(buildKey(userId, idempotencyKey), orderDTO, TTL);
    }

    public void clear(String userId, String idempotencyKey) {
        redisTemplate.delete(buildKey(userId, idempotencyKey));
    }

    private String buildKey(String userId, String idempotencyKey) {
        return KEY_PREFIX + userId + ":" + idempotencyKey;
    }
}
