package com.shop.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Controller 方法参数需要注入当前登录用户。
 * <p>
 * Spring MVC 会通过 CurrentUserArgumentResolver 解析该注解：
 * 优先从 Authorization JWT 中解析用户，失败时再从 X-Session-ID 对应的 Redis Session 中解析用户。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
