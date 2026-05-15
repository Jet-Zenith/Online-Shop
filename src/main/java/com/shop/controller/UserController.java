package com.shop.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.CurrentUser;
import com.shop.common.Result;
import com.shop.dto.LoginRequest;
import com.shop.dto.RegisterRequest;
import com.shop.dto.UserDTO;
import com.shop.exception.BusinessException;
import com.shop.model.User;
import com.shop.service.JwtTokenService;
import com.shop.service.TokenRevocationService;
import com.shop.service.UserService;
import org.apache.commons.lang3.StringUtils;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;

    public UserController(UserService userService,
                          JwtTokenService jwtTokenService,
                          TokenRevocationService tokenRevocationService) {
        this.userService = userService;
        this.jwtTokenService = jwtTokenService;
        this.tokenRevocationService = tokenRevocationService;
    }

    @PostMapping("/register")
    public Result<UserDTO> register(@Valid @RequestBody RegisterRequest request) {
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(request.getPassword())
                .build();
        return Result.success(convertToDTO(userService.createUser(user)));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.getUserByUsername(request.getUsername());
        if (user == null || !userService.verifyPassword(request.getPassword(), user.getPassword())) {
            throw BusinessException.unauthorized("Invalid username or password");
        }

        Map<String, Object> data = Map.of(
                "sessionId", userService.createSession(user.getId()),
                "accessToken", jwtTokenService.generateToken(user),
                "tokenType", "Bearer",
                "expiresIn", jwtTokenService.getExpirationSeconds(),
                "user", convertToDTO(user)
        );
        return Result.success(data);
    }

    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = "X-Session-ID", required = false) String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (StringUtils.isNotBlank(sessionId)) {
            userService.deleteSession(sessionId);
        }
        if (StringUtils.isNotBlank(authorization) && authorization.startsWith("Bearer ")) {
            JwtTokenService.JwtClaims claims = jwtTokenService.parseToken(authorization.substring("Bearer ".length()));
            tokenRevocationService.revoke(claims.tokenId(), claims.expiresAt());
        }
        return Result.success();
    }

    @GetMapping("/profile")
    public Result<UserDTO> getProfile(@CurrentUser User user) {
        return Result.success(convertToDTO(user));
    }

    @GetMapping("/validate-session")
    public Result<Map<String, Object>> validateSession(@CurrentUser User user) {
        return Result.success(Map.of(
                "valid", true,
                "userId", user.getId(),
                "user", convertToDTO(user)
        ));
    }

    @GetMapping("/page")
    public Result<Page<UserDTO>> getUsersPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        Page<User> pageResult = userService.getUsersByPage(pageNum, pageSize);
        Page<UserDTO> dtoPage = new Page<>(pageResult.getCurrent(), pageResult.getSize(), pageResult.getTotal());

        List<UserDTO> dtoList = pageResult.getRecords().stream()
                .map(this::convertToDTO)
                .toList();

        dtoPage.setRecords(dtoList);
        return Result.success(dtoPage);
    }

    private UserDTO convertToDTO(User user) {
        if (user == null) {
            return null;
        }
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        return userDTO;
    }
}
