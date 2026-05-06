package com.shop.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.Result;
import com.shop.dto.UserDTO;
import com.shop.model.User;
import com.shop.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<UserDTO> register(@RequestBody User user) {
        User createdUser = userService.createUser(user);
        return Result.success(convertToDTO(createdUser));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody User loginRequest) {
        User user = userService.getUserByUsername(loginRequest.getUsername());
        if (user == null || !userService.verifyPassword(loginRequest.getPassword(), user.getPassword())) {
            return Result.error(401, "用户名或密码错误");
        }
        String sessionId = userService.createSession(user.getId());

        Map<String, Object> data = Map.of(
                "sessionId", sessionId,
                "user", convertToDTO(user)
        );

        return Result.success(data);
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("X-Session-ID") String sessionId) {
        userService.deleteSession(sessionId);
        return Result.success();
    }

    @GetMapping("/profile")
    public Result<UserDTO> getProfile(@RequestHeader("X-Session-ID") String sessionId) {
        User user = userService.getUserBySession(sessionId);

        if (user == null) {
            return Result.error(401, "凭证无效");
        }
        return Result.success(convertToDTO(user));
    }

    @GetMapping("/validate-session")
    public Result<Map<String, Object>> validateSession(@RequestHeader("X-Session-ID") String sessionId) {
        if (!userService.validateSession(sessionId)) {
            return Result.error(401, "会话已过期或无效，请重新登录");
        }
        User user = userService.getUserBySession(sessionId);

        Map<String, Object> data = Map.of(
                "valid", true,
                "userId", user.getId(),
                "user", convertToDTO(user)
        );
        return Result.success(data);
    }

    @GetMapping("/page")
    public Result<Page<UserDTO>> getUsersPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        Page<User> pageResult = userService.getUsersByPage(pageNum, pageSize);
        Page<UserDTO> dtoPage = new Page<>(pageResult.getCurrent(), pageResult.getSize(), pageResult.getTotal());

        List<UserDTO> dtoList = pageResult.getRecords().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        dtoPage.setRecords(dtoList);
        return Result.success(dtoPage);
    }

    private UserDTO convertToDTO(User user) {
        if (user == null) return null;
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        return userDTO;
    }
}
