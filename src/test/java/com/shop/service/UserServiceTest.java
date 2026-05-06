package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.mapper.UserMapper;
import com.shop.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserMapper userMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private UserService userService;

    private final User testUser = User.builder()
            .id("user_001")
            .username("testuser")
            .email("test@example.com")
            .password("raw_password")
            .build();

    @Test
    void createUserShouldEncodePassword() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(passwordEncoder.encode("raw_password")).thenReturn("hashed_password");
        when(userMapper.insert(any(User.class))).thenReturn(1);

        User result = userService.createUser(testUser);

        verify(passwordEncoder).encode("raw_password");
        assertEquals("hashed_password", result.getPassword());
        verify(valueOperations).set(eq("user:user_001"), eq(result), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    void getUserByIdShouldReturnFromCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:user_001")).thenReturn(testUser);

        User result = userService.getUserById("user_001");

        assertNotNull(result);
        assertEquals("testuser", result.getUsername());
        verify(userMapper, never()).selectById(anyString());
    }

    @Test
    void getUserByIdShouldFallbackToDatabase() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:user_001")).thenReturn(null);
        when(userMapper.selectById("user_001")).thenReturn(testUser);

        User result = userService.getUserById("user_001");

        assertNotNull(result);
        verify(userMapper).selectById("user_001");
        verify(valueOperations).set(eq("user:user_001"), eq(testUser), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    void validateSessionShouldReturnFalseForInvalidSession() {
        when(redisTemplate.hasKey("session:invalid-session")).thenReturn(false);

        boolean result = userService.validateSession("invalid-session");

        assertFalse(result);
    }

    @Test
    void validateSessionShouldReturnTrueForValidSession() {
        when(redisTemplate.hasKey("session:valid-session")).thenReturn(true);

        boolean result = userService.validateSession("valid-session");

        assertTrue(result);
    }

    @Test
    void testSessionBackdoorShouldNotExist() {
        when(redisTemplate.hasKey("session:test-session-123")).thenReturn(false);

        boolean result = userService.validateSession("test-session-123");

        assertFalse(result, "Test backdoor must not exist");
    }

    @Test
    void verifyPasswordShouldDelegateToEncoder() {
        when(passwordEncoder.matches("raw", "hashed")).thenReturn(true);

        assertTrue(userService.verifyPassword("raw", "hashed"));
    }

    @Test
    void getUserBySessionShouldReturnNullForInvalidSession() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("session:bad-session")).thenReturn(null);

        User result = userService.getUserBySession("bad-session");

        assertNull(result);
    }

    @Test
    void deleteSessionShouldRemoveBothKeys() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("session:sess_123")).thenReturn(testUser);

        userService.deleteSession("sess_123");

        verify(redisTemplate).delete("session:user:user_001");
        verify(redisTemplate).delete("session:sess_123");
    }
}
