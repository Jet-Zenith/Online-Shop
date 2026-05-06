package com.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class RedisOnlineShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisOnlineShopApplication.class, args);
    }
}