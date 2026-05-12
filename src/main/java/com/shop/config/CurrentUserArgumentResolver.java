package com.shop.config;

import com.shop.common.CurrentUser;
import com.shop.common.BaseContext;
import com.shop.exception.BusinessException;
import com.shop.model.User;
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

    private final UserService userService;

    public CurrentUserArgumentResolver(UserService userService) {
        this.userService = userService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && User.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String sessionId = webRequest.getHeader(SESSION_HEADER);
        if (StringUtils.isBlank(sessionId)) {
            throw BusinessException.unauthorized("Please sign in first");
        }

        User user = userService.getUserBySession(sessionId);
        if (user == null) {
            throw BusinessException.unauthorized("Session expired or invalid");
        }
        BaseContext.setCurrentId(user.getId());
        return user;
    }
}
