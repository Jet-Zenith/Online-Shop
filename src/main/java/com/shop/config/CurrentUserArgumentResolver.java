package com.shop.config;

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

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        User user = resolveByJwt(webRequest);
        if (user == null) {
            user = resolveBySession(webRequest);
        }
        if (user == null) {
            throw BusinessException.unauthorized("Please sign in first");
        }
        BaseContext.setCurrentId(user.getId());
        return user;
    }

    private User resolveByJwt(NativeWebRequest webRequest) {
        String authorization = webRequest.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.isBlank(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        JwtTokenService.JwtClaims claims = jwtTokenService.parseToken(token);
        if (tokenRevocationService.isRevoked(claims.tokenId())) {
            throw BusinessException.unauthorized("Token has been revoked");
        }
        User user = userService.getUserById(claims.userId());
        if (user == null) {
            throw BusinessException.unauthorized("Token user no longer exists");
        }
        return user;
    }

    private User resolveBySession(NativeWebRequest webRequest) {
        String sessionId = webRequest.getHeader(SESSION_HEADER);
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }

        User user = userService.getUserBySession(sessionId);
        if (user == null) {
            throw BusinessException.unauthorized("Session expired or invalid");
        }
        return user;
    }
}
