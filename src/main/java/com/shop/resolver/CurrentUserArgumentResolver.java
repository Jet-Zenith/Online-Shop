package com.shop.resolver;

import com.shop.common.CurrentUser;
import com.shop.common.BaseContext;
import com.shop.exception.BusinessException;
import com.shop.model.User;
import com.shop.service.JwtTokenService;
import com.shop.service.TokenRevocationService;
import com.shop.service.UserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String SESSION_HEADER = "X-Session-ID";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserService userService;
    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;

    public CurrentUserArgumentResolver(UserService userService,
                                       JwtTokenService jwtTokenService,
                                       TokenRevocationService tokenRevocationService) {
        this.userService = userService;
        this.jwtTokenService = jwtTokenService;
        this.tokenRevocationService = tokenRevocationService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && User.class.isAssignableFrom(parameter.getParameterType());
    }

    /**
     * 解析带有 @CurrentUser 注解的 Controller 参数
     * <p>
     * 解析顺序：
     * 1. 优先从 Authorization: Bearer <token> 中解析 JWT 用户。
     * 2. 如果没有 JWT，则从 X-Session-ID 中解析 Redis Session 用户。
     * 3. 两种方式都失败时，抛出 401 未登录异常。
     *
     * @param parameter     当前待解析的方法参数
     * @param mavContainer  Spring MVC 模型视图容器，本方法未使用
     * @param webRequest    当前 HTTP 请求包装对象，用于读取请求头
     * @param binderFactory 参数绑定工厂，本方法未使用
     * @return 当前登录用户 User 对象
     */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        // 1. 优先使用 JWT 鉴权。JWT 适合无状态接口调用，前端通常放在 Authorization 请求头中。
        User user = resolveByJwt(webRequest);
        if (user == null) {
            // 2. JWT 不存在时，降级使用 Redis Session。兼容需要服务端会话控制的场景。
            user = resolveBySession(webRequest);
        }
        if (user == null) {
            // 3. 两种凭证都没有解析出用户，说明当前请求未登录或登录态已失效。
            throw BusinessException.unauthorized("Please sign in first");
        }
        // 4. 保存当前用户 ID，供 MyBatis 自动填充 createUser/updateUser 等审计字段使用。
        BaseContext.setCurrentId(user.getId());
        return user;
    }

    /**
     * 通过 Authorization 请求头中的 JWT 解析当前用户。
     * <p>
     * 适用于前端携带 Bearer Token 的无状态鉴权场景。
     *
     * @param webRequest 当前 HTTP 请求包装对象
     * @return 解析成功的用户；如果请求中没有 JWT，则返回 null，让外层继续尝试 Session 解析
     */
    private User resolveByJwt(NativeWebRequest webRequest) {
        // 1. 从 Authorization 请求头中读取 Bearer Token。
        String authorization = webRequest.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.isBlank(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            // 没有 JWT 不代表一定未登录，外层还会继续尝试 X-Session-ID。
            return null;
        }

        // 2. 去掉 "Bearer " 前缀，得到真正的 JWT 字符串。
        String token = authorization.substring(BEARER_PREFIX.length());

        // 3. 校验 JWT 签名和过期时间，并解析出 userId、tokenId 等声明。
        JwtTokenService.JwtClaims claims = jwtTokenService.parseToken(token);

        // 4. 检查 token 是否已经被加入 Redis 黑名单，例如用户主动登出后旧 token 不能继续使用。
        if (tokenRevocationService.isRevoked(claims.tokenId())) {
            throw BusinessException.unauthorized("Token has been revoked");
        }

        // 5. 根据 JWT 中的 userId 查询用户，确保 token 指向的用户仍然存在。
        User user = userService.getUserById(claims.userId());
        if (user == null) {
            throw BusinessException.unauthorized("Token user no longer exists");
        }
        return user;
    }

    /**
     * 通过 X-Session-ID 请求头中的 Redis Session 解析当前用户。
     * <p>
     * 适用于需要服务端会话控制的场景，例如踢人下线、限制单设备登录或兼容传统 Session 鉴权。
     *
     * @param webRequest 当前 HTTP 请求包装对象
     * @return 解析成功的用户；如果请求中没有 Session ID，则返回 null
     */
    private User resolveBySession(NativeWebRequest webRequest) {
        // 1. 从 X-Session-ID 请求头中读取 sessionId。
        String sessionId = webRequest.getHeader(SESSION_HEADER);
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }

        // 2. 根据 sessionId 从 Redis 中读取用户对象。
        User user = userService.getUserBySession(sessionId);
        if (user == null) {
            // sessionId 存在但 Redis 查不到，说明会话已过期、被删除或本身无效。
            throw BusinessException.unauthorized("Session expired or invalid");
        }
        return user;
    }
}
