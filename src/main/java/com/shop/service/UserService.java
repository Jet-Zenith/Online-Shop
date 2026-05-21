package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.exception.BusinessException;
import com.shop.mapper.UserMapper;
import com.shop.model.User;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String USER_KEY_PREFIX = "user:";
    private static final String USER_SESSION_KEY_PREFIX = "session:";
    private static final long USER_CACHE_TTL_HOURS = 24;

    public UserService(PasswordEncoder passwordEncoder, UserMapper userMapper, RedisTemplate<String, Object> redisTemplate) {
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 创建新用户
     * 包含参数校验、唯一性冲突检测、密码安全哈希以及缓存主动预热功能。
     *
     * @param user 包含用户注册信息（用户名、明文密码、邮箱等）的实体对象
     * @return 创建成功并分配了 ID 且脱敏处理后的 User 对象
     */
    public User createUser(User user) {
        // 1. 基础参数边界校验 (Fail-Fast)
        if (StringUtils.isBlank(user.getUsername()) || StringUtils.isBlank(user.getPassword())) {
            throw BusinessException.badRequest("Username and password are required");
        }
        // 2. 唯一性冲突检测
        // 注意：这里的代码级校验是为了快速响应前端。
        // TODO：在高并发场景下为防并发穿透，数据库层面仍需对 username 和 email 建立 Unique Index 兜底。
        if (getUserByUsername(user.getUsername()) != null) {
            throw BusinessException.conflict("Username already exists");
        }
        if (StringUtils.isNotBlank(user.getEmail()) && emailExists(user.getEmail())) {
            throw BusinessException.conflict("Email already exists");
        }
        // 3. ID 生成策略
        // TODO：如果上游没有传入 ID，则使用 UUID 保证全局唯一（若采用分布式自增雪花算法，可在此处替换）
        if (StringUtils.isBlank(user.getId())) {
            user.setId(UUID.randomUUID().toString());
        }
        // 4. 密码哈希
        // 严禁明文密码入库！使用 BCrypt 等强哈希算法进行加盐加密
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        // 5. 数据落盘
        userMapper.insert(user);

        // 6. 缓存主动预热 & 冗余双写
        // 注册成功后立刻将数据写入 Redis，确保用户随后的首次登录或查询极速响应
        String userKey = USER_KEY_PREFIX + user.getId();
        // 按 userId 缓存，服务于内部业务流转
        redisTemplate.opsForValue().set(userKey, user, USER_CACHE_TTL_HOURS, TimeUnit.HOURS);
        // 按 username 缓存，服务于用户后续的账号密码登录场景
        redisTemplate.opsForValue().set(usernameKey(user.getUsername()), user, USER_CACHE_TTL_HOURS, TimeUnit.HOURS);

        return user;
    }

    public User getUserById(String userId) {
        String userKey = USER_KEY_PREFIX + userId;
        User user = (User) redisTemplate.opsForValue().get(userKey);

        if (user == null) {
            user = userMapper.selectById(userId);
            if (user != null) {
                redisTemplate.opsForValue().set(userKey, user, USER_CACHE_TTL_HOURS, TimeUnit.HOURS);
            }
        }
        return user;
    }

    /**
     * 根据用户名获取用户信息（采用 Cache-Aside 旁路缓存模式）
     *
     * @param username 用户名
     * @return User 实体对象，不存在则返回 null
     */
    public User getUserByUsername(String username) {
        // 1. 边界校验：防止无效请求进行后续耗时操作
        if (StringUtils.isBlank(username)) {
            return null;
        }
        // 2. 查缓存：尝试从 Redis 中获取用户信息（Key 例: user:username::admin）
        String usernameKey = usernameKey(username);
        User user = (User) redisTemplate.opsForValue().get(usernameKey);

        // 3. 缓存未命中：触发数据库回源机制
        if (user == null) {
            // 使用 MyBatis-Plus 的 LambdaQueryWrapper，保证字段引用的类型安全
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getUsername, username);
            user = userMapper.selectOne(wrapper);

            // 4. 缓存回写：如果数据库存在该用户，则同步到 Redis
            if (user != null) {
                // 策略 A：按 username 缓存，服务于后续的登录场景
                redisTemplate.opsForValue().set(usernameKey, user, USER_CACHE_TTL_HOURS, TimeUnit.HOURS);
                // 策略 B：按 userId 缓存（冗余双写），服务于系统内其他依赖 userId 查询的业务模块，提前热身
                String userKey = USER_KEY_PREFIX + user.getId();
                redisTemplate.opsForValue().set(userKey, user, USER_CACHE_TTL_HOURS, TimeUnit.HOURS);
            }
        }
        // 5. 返回结果
        return user;
    }

    private boolean emailExists(String email) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getEmail, email);
        Long count = userMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    /**
     * 生成用于 Redis 缓存的 Username Key
     * 采用统一的冒号分隔命名规范，便于在 Redis 可视化工具中以树形目录查看，
     * 同时消除代码中的魔法字符串，集中管理 Key 的生成规则。
     *
     * @param username 用户的登录名
     * @return 拼接好的 Redis Key，例如 "user:username:admin"
     */
    private String usernameKey(String username) {
        return "user:username:" + username;
    }

    public String createSession(String userId) {
        String sessionId = UUID.randomUUID().toString();
        String sessionKey = USER_SESSION_KEY_PREFIX + sessionId;
        User user = getUserById(userId);

        if (user != null) {
            redisTemplate.opsForValue().set(sessionKey, user, 24, TimeUnit.HOURS);
            redisTemplate.opsForValue().set("session:user:" + userId, sessionId, 24, TimeUnit.HOURS);
        }

        return sessionId;
    }

    public User getUserBySession(String sessionId) {
        String sessionKey = USER_SESSION_KEY_PREFIX + sessionId;
        return (User) redisTemplate.opsForValue().get(sessionKey);
    }

    public void deleteSession(String sessionId) {
        String sessionKey = USER_SESSION_KEY_PREFIX + sessionId;
        User user = (User) redisTemplate.opsForValue().get(sessionKey);

        if (user != null) {
            redisTemplate.delete("session:user:" + user.getId());
        }
        redisTemplate.delete(sessionKey);
    }

    /**
     * 校验会话 (Session) 的有效性
     * 通常被各种安全拦截器 (Interceptor) 或过滤器 (Filter) 在请求到达 Controller 前高频调用
     *
     * @param sessionId 前端（通常通过 Header）传来的会话 ID
     * @return true 表示会话依然存活且有效，false 表示已过期或被强制踢下线
     */
    public boolean validateSession(String sessionId) {
        // 1. 拼接要校验的 Redis Key
        String sessionKey = USER_SESSION_KEY_PREFIX + sessionId;
        // 2. 高效验活与安全拆箱
        // - 性能优化：使用 hasKey (Redis EXISTS) 而不是 get，避免反序列化大对象的网络与 CPU 开销
        // - 健壮性：使用 Boolean.TRUE.equals 避免包装类返回 null 时引发的自动拆箱空指针异常 (NPE)
        return Boolean.TRUE.equals(redisTemplate.hasKey(sessionKey));
    }

    public boolean verifyPassword(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    public Page<User> getUsersByPage(int pageNum, int pageSize) {
        Page<User> page = new Page<>(pageNum, pageSize);
        return userMapper.selectPage(page, null);
    }
}
