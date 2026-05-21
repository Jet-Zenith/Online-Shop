package com.shop;

import com.shop.event.OrderEventProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties(OrderEventProperties.class)
public class RedisOnlineShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisOnlineShopApplication.class, args);
    }
}
