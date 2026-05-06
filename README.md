# Redis Online Shop

基于 Spring Boot + Redis + MySQL 的在线商城系统。

## 功能特性

- 商品管理：商品的增删改查、热门商品缓存、关键字搜索、分页查询
- 购物车功能：添加、删除、修改购物车商品数量、合并购物车、结算
- 用户管理：用户注册、登录、会话管理、分页查询
- Redis 缓存：商品信息、购物车、用户会话的缓存，支持分布式部署

## 技术栈

- Spring Boot 3.2.5
- Spring Data Redis (Lettuce)
- Spring Security (BCrypt)
- MyBatis-Plus 3.5.5
- MySQL 8.x
- Maven 3.x
- Java 21

## 快速开始

### 1. 环境准备

确保已安装并启动以下服务：
- MySQL（默认端口 3306）
- Redis（默认端口 6379）

### 2. 创建数据库

```sql
CREATE DATABASE IF NOT EXISTS online_shop DEFAULT CHARSET utf8mb4;
```

应用启动时会自动执行 `schema.sql` 创建表结构。

### 3. 配置环境变量（必须）

项目启动需要设置以下环境变量（无默认值，不设置会启动失败）：

```bash
# 复制模板文件
cp .env.example .env

# 编辑 .env 填入真实值
vim .env
```

| 变量 | 必填 | 说明 |
|------|------|------|
| `DB_USERNAME` | 是 | 数据库用户名 |
| `DB_PASSWORD` | 是 | 数据库密码 |
| `DB_HOST` | 否 | MySQL 主机，默认 localhost |
| `DB_PORT` | 否 | MySQL 端口，默认 3306 |
| `DB_NAME` | 否 | 数据库名，默认 online_shop |
| `REDIS_HOST` | 否 | Redis 主机，默认 localhost |
| `REDIS_PORT` | 否 | Redis 端口，默认 6379 |
| `JWT_SECRET` | 是 | JWT 签名密钥（至少 32 字符） |
| `SERVER_PORT` | 否 | 服务端口，默认 8080 |

### 4. 运行项目

```bash
cd redis-online-shop
mvn clean compile
mvn spring-boot:run
```

或者使用 `start.bat` 一键启动。

### 5. 测试 API

```bash
# 注册
curl -X POST http://localhost:8080/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"username":"john_doe","email":"john@example.com","password":"password123"}'

# 登录
curl -X POST http://localhost:8080/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"username":"john_doe","password":"password123"}'

# 获取所有商品
curl http://localhost:8080/api/products

# 搜索商品
curl "http://localhost:8080/api/products/search?keyword=iphone"
```

## API 列表

### 用户相关
- `POST /api/users/register` - 注册
- `POST /api/users/login` - 登录
- `POST /api/users/logout` - 登出（需 X-Session-ID 头）
- `GET /api/users/profile` - 获取个人信息
- `GET /api/users/validate-session` - 校验会话
- `GET /api/users/page?pageNum=1&pageSize=10` - 分页查询

### 商品相关
- `GET /api/products` - 获取所有商品
- `GET /api/products/{id}` - 获取单个商品
- `POST /api/products` - 添加商品
- `PUT /api/products/{id}` - 更新商品
- `DELETE /api/products/{id}` - 删除商品
- `GET /api/products/hot` - 热门商品（库存最少 TOP5）
- `GET /api/products/search?keyword=&category=` - 搜索商品
- `GET /api/products/page?pageNum=1&pageSize=10` - 分页查询

### 购物车相关（均需 X-Session-ID 头）
- `GET /api/cart` - 获取购物车
- `POST /api/cart/items` - 添加商品
- `DELETE /api/cart/items/{productId}` - 移除商品
- `PUT /api/cart/items/{productId}?quantity=` - 修改数量
- `DELETE /api/cart` - 清空购物车
- `POST /api/cart/merge` - 合并购物车
- `POST /api/cart/checkout` - 结算

## Redis 缓存说明

| Key 模式 | 内容 | TTL |
|----------|------|-----|
| `product:{id}` | 单个商品 | 1 小时 |
| `all:products` | 全部商品列表 | 1 小时 |
| `hot:products` | 热门商品 | 30 分钟 |
| `cart:{userId}` | 用户购物车 | 24 小时 |
| `session:{sessionId}` | 用户会话 | 24 小时 |
| `user:{id}` | 用户信息 | 24 小时 |
| `user:username:{name}` | 用户名索引 | 24 小时 |

## 项目结构

```
redis-online-shop/
├── src/main/java/com/shop/
│   ├── common/
│   │   ├── BaseContext.java
│   │   └── Result.java
│   ├── config/
│   │   ├── MyMetaObjectHandler.java
│   │   ├── MybatisPlusConfig.java
│   │   ├── RedisConfig.java
│   │   └── SecurityConfig.java
│   ├── controller/
│   │   ├── CartController.java
│   │   ├── ProductController.java
│   │   └── UserController.java
│   ├── dto/
│   │   ├── CartRequest.java
│   │   ├── ProductRequest.java
│   │   └── UserDTO.java
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   ├── InsufficientStockException.java
│   │   └── ProductNotFoundException.java
│   ├── mapper/
│   │   ├── ProductMapper.java
│   │   └── UserMapper.java
│   ├── model/
│   │   ├── Cart.java
│   │   ├── CartItem.java
│   │   ├── Product.java
│   │   └── User.java
│   └── service/
│       ├── CartService.java
│       ├── ProductService.java
│       └── UserService.java
├── src/main/resources/
│   ├── application.yml
│   └── schema.sql
├── src/test/java/com/shop/
│   └── service/
│       ├── CartServiceTest.java
│       ├── ProductServiceTest.java
│       └── UserServiceTest.java
└── pom.xml
```

## 开发说明

1. 购物车数据完全存储在 Redis 中，无对应 MySQL 表
2. 用户会话通过 Redis 管理，支持分布式部署
3. 结算采用原子 SQL 扣减库存（`UPDATE ... SET stock = stock - ? WHERE id = ? AND stock >= ?`），防止超卖
4. CORS 在 SecurityConfig 中统一配置
5. 敏感配置支持环境变量覆盖
