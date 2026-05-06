package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.mapper.UserMapper;
import com.shop.model.User;
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

    public User createUser(User user) {
        if (user.getId() == null || user.getId().isEmpty()) {
            user.setId(UUID.randomUUID().toString());
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        userMapper.insert(user);

        String userKey = USER_KEY_PREFIX + user.getId();
        redisTemplate.opsForValue().set(userKey, user, USER_CACHE_TTL_HOURS, TimeUnit.HOURS);

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

    public User getUserByUsername(String username) {
        String usernameKey = "user:username:" + username;
        User user = (User) redisTemplate.opsForValue().get(usernameKey);

        if (user == null) {
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getUsername, username);
            user = userMapper.selectOne(wrapper);

            if (user != null) {
                redisTemplate.opsForValue().set(usernameKey, user, USER_CACHE_TTL_HOURS, TimeUnit.HOURS);
                String userKey = USER_KEY_PREFIX + user.getId();
                redisTemplate.opsForValue().set(userKey, user, USER_CACHE_TTL_HOURS, TimeUnit.HOURS);
            }
        }
        return user;
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

    public boolean validateSession(String sessionId) {
        String sessionKey = USER_SESSION_KEY_PREFIX + sessionId;
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
