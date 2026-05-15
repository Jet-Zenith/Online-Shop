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

    /**
     * 用户登录接口
     * 采用 JWT 与 Redis Session 混合架构，提供无状态鉴权与灵活的会话控制（如踢人下线）。
     *
     * @param request 包含用户名和明文密码的请求体（入口处已通过 @Valid 触发 JSR-303 参数基础校验）
     * @return 统一封装的 Result 对象，内部携带 Token 凭证及脱敏后的用户基础信息
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        // 1. 查库：根据前端传入的用户名，从数据库中拉取用户完整信息
        User user = userService.getUserByUsername(request.getUsername());
        // 2. 核心安全防御：Fail-Fast (快速失败)
        // 逻辑：如果用户不存在，或者密码比对失败
        if (user == null || !userService.verifyPassword(request.getPassword(), user.getPassword())) {
            throw BusinessException.unauthorized("Invalid username or password");
        }
        // 3. 颁发凭证与数据脱装
        // 使用 Map.of 构建不可变的只读字典，杜绝后续代码误改，且内部自带防 NullPointerException 机制
        Map<String, Object> data = Map.of(
                // 会话 ID：持久化到 Redis 中，方便后端掌控全局登录状态（如限制单设备登录）
                "sessionId", userService.createSession(user.getId()),
                // 访问令牌：签发 JWT 字符串，前端后续每次请求都会在 Header 中携带它
                "accessToken", jwtTokenService.generateToken(user),
                // 令牌类型：遵循 OAuth2.0 规范，声明这是一个 Bearer Token
                "tokenType", "Bearer",
                // 过期时间：将 JWT 的有效秒数告诉前端，方便前端判断是否需要静默刷新 Token 或重新引导登录
                "expiresIn", jwtTokenService.getExpirationSeconds(),
                // 数据脱敏：严禁把包含密码哈希的 User 实体直接扔给前端！必须转换为 DTO，只保留头像、昵称等安全字段
                "user", convertToDTO(user)
        );
        // 4. 统一响应：套上带有统一状态码 (Code) 和消息 (Message) 的外壳返回
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
