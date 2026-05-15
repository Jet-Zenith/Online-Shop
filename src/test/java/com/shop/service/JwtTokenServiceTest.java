package com.shop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.exception.BusinessException;
import com.shop.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenServiceTest {

    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void generatedTokenShouldExposeClaims() {
        JwtTokenService jwtTokenService = new JwtTokenService(new ObjectMapper(), SECRET, 3600000);
        User user = User.builder()
                .id("user_001")
                .username("alice")
                .build();

        String token = jwtTokenService.generateToken(user);
        JwtTokenService.JwtClaims claims = jwtTokenService.parseToken(token);

        assertEquals("user_001", claims.userId());
        assertEquals("user_001", jwtTokenService.extractUserId(token));
    }

    @Test
    void tamperedTokenShouldBeRejected() {
        JwtTokenService jwtTokenService = new JwtTokenService(new ObjectMapper(), SECRET, 3600000);
        User user = User.builder()
                .id("user_001")
                .username("alice")
                .build();

        String token = jwtTokenService.generateToken(user);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThrows(BusinessException.class, () -> jwtTokenService.parseToken(tampered));
    }
}
