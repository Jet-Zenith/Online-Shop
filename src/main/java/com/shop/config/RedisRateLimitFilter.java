package com.shop.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
public class RedisRateLimitFilter extends OncePerRequestFilter {

    // Redis Key 的统一前缀，为了防止和系统里别的缓存数据撞名
    private static final String RATE_LIMIT_PREFIX = "rate-limit:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final boolean enabled;       // 限流开关
    private final int maxRequests;       // 最大请求次数
    private final Duration window;       // 时间窗口大小

    public RedisRateLimitFilter(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.max-requests:120}") int maxRequests,
            @Value("${app.rate-limit.window-seconds:60}") long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.maxRequests = maxRequests;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    /**
     * 拦截器的核心执行逻辑：执行 Redis 固定窗口限流
     *
     * @param request     HTTP 请求
     * @param response    HTTP 响应
     * @param filterChain 过滤器链（用于放行请求）
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 1. 获取根据 IP、接口、时间切片生成的唯一限流锁标识
        String key = buildKey(request);
        try {
            // 2. 原子计数 (核心高并发防御)
            // 使用 Redis 的 INCR 指令（单线程原子操作），绝对避免并发请求导致的数据覆盖问题
            Long current = redisTemplate.opsForValue().increment(key);
            // 3. 窗口初始化
            if (current != null && current == 1L) {
                // 如果是该时间窗口内的第一个请求，立刻为该 Key 设置过期时间
                // 保证时间窗口（如 60s）结束后，计数器能被自动清理重置
                redisTemplate.expire(key, window);
            }
            // 4. 阈值判定：触发限流
            if (current != null && current > maxRequests) {
                // 设置符合国际标准的 HTTP 状态码：429 Too Many Requests
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                // 在响应头中明确告知调用方：限流上限是多少，当前剩余 0 次
                response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequests));
                response.setHeader("X-RateLimit-Remaining", "0");
                // 返回友好的 JSON 错误提示
                response.getWriter().write("{\"code\":429,\"message\":\"Too many requests\"}");
                return;
            }
            // 5. 正常放行：写入提示响应头
            if (current != null) {
                // 未触发限流，将当前的额度消耗情况放在 Header 中告知前端
                response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequests));
                response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, maxRequests - current)));
            }
        } catch (RuntimeException ex) {
            // 6. 顶级防灾架构设计：降级策略 (Fail-Open)
            // 将整个 Redis 交互包裹在 try-catch 中。
            // 如果 Redis 宕机或网络超时，记录警告日志，但代码继续往下走，直接放行请求。
            // 核心理念：宁可让后台数据库短暂承压，也绝不能因为非核心组件（限流器）故障导致整个系统 100% 瘫痪。
            log.warn("Rate limiter failed open: {}", ex.getMessage());
        }
        // 7. 将请求移交给下一个过滤器，或进入 Controller 业务逻辑
        filterChain.doFilter(request, response);
    }

    /**
     * 构建 Redis 限流计数的唯一 Key
     * 采用多维度拼接与时间切片算法，实现细粒度的接口级限流。
     * 格式示例：rate-limit:192.168.1.1:POST:/api/order:28333333
     *
     * @param request HTTP 请求对象
     * @return 拼接完整的 Redis Key
     */
    private String buildKey(HttpServletRequest request) {
        // 1. 时间切片计算（核心数学逻辑）
        // 将当前绝对时间戳除以窗口大小（向下取整）。
        // 保证在同一个时间窗口（如 60s）内的所有请求，都能算出完全相同的 windowId。
        long windowId = System.currentTimeMillis() / window.toMillis();
        // 2. 组装多维度 Key，使用冒号 (:) 分隔符合 Redis 目录树设计规范
        return RATE_LIMIT_PREFIX
                + clientIp(request) + ":"          // 维度 1：按客户端 IP 隔离
                + request.getMethod() + ":"        // 维度 2：按 HTTP 动作隔离（GET / POST 等）
                + request.getRequestURI() + ":"    // 维度 3：按具体接口隔离
                + windowId;                        // 维度 4：按时间切片隔离
    }

    /**
     * 获取客户端真实物理 IP 地址
     * 核心目的：穿透 Nginx、阿里云 SLB 等反向代理服务器，获取客户端真实物理 IP 地址，
     * 防止限流器将代理服务器的内网 IP 当作用户 IP，从而引发“全网误杀”的生产事故。
     *
     * @param request HTTP 请求对象
     * @return 真实用户的公网 IP 或直连 IP
     */
    private String clientIp(HttpServletRequest request) {
        // 1. 尝试从 HTTP 标准反向代理头 X-Forwarded-For 中获取 IP 追踪记录
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // 2. 核心细节：处理多层代理的场景
            // 如果请求经过了多个代理（如：用户 -> 代理A -> 代理B -> 服务器），
            // X-Forwarded-For 的值会变成类似 "114.114.x.x, 192.168.1.1, 10.0.0.1" 的逗号分隔串。
            // 规范中，最左边的第一个 IP 永远是最初始的真实用户 IP。
            return forwardedFor.split(",")[0].trim();
        }
        // 3. 兜底方案 (Fallback)
        // 如果没有找到该请求头，说明请求没有经过代理（如本地 localhost 调试，或内部微服务直连），
        // 此时直接调用原生方法获取建立 TCP 连接的对端 IP。
        return request.getRemoteAddr();
    }
}
