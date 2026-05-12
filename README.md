# Redis Online Shop

一个基于 Spring Boot 3、Redis、MySQL 和 MyBatis-Plus 的在线商城后端。项目包括了注册登录、会话、购物车、库存扣减、订单落库、分页查询、统一异常、请求追踪和健康检查等企业后端常见能力。

## 技术栈

- Java 21
- Spring Boot 3.2.5
- Spring Web / Validation / Security / Actuator
- Spring Data Redis
- MyBatis-Plus 3.5.5
- MySQL 8.x
- Maven
- JUnit 5 + Mockito

## 核心能力

- 用户注册、登录、退出、会话校验
- 商品创建、更新、删除、详情、搜索、热门商品、分页
- Redis 缓存商品、用户、会话和购物车数据
- 购物车增删改查、合并、清空和结算
- 结算时通过数据库条件更新保护库存，避免超卖
- 结算成功后生成订单和订单明细，保存商品快照价格
- 统一认证参数解析器，业务接口可直接注入当前用户
- 统一异常响应，返回 traceId 方便排查问题
- Actuator 健康检查和基础指标

## 快速启动

1. 创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS online_shop DEFAULT CHARSET utf8mb4;
```

2. 准备环境变量：

```bash
cp .env.example .env
```

3. 修改 `.env` 中的 MySQL、Redis 和密钥配置。

4. 启动项目：

```bash
mvn spring-boot:run
```

应用默认启动在 `http://localhost:8080`。首次启动会执行 `src/main/resources/schema.sql` 初始化表结构。

## 常用接口

### 用户

- `POST /api/users/register`
- `POST /api/users/login`
- `POST /api/users/logout`
- `GET /api/users/profile`
- `GET /api/users/validate-session`
- `GET /api/users/page?pageNum=1&pageSize=10`

### 商品

- `GET /api/products`
- `GET /api/products/{id}`
- `POST /api/products`
- `PUT /api/products/{id}`
- `DELETE /api/products/{id}`
- `GET /api/products/hot`
- `GET /api/products/search?keyword=phone&category=electronics`
- `GET /api/products/page?pageNum=1&pageSize=10`

### 购物车

以下接口需要请求头 `X-Session-ID`：

- `GET /api/cart`
- `POST /api/cart/items`
- `PUT /api/cart/items/{productId}?quantity=2`
- `DELETE /api/cart/items/{productId}`
- `DELETE /api/cart`
- `POST /api/cart/merge`
- `POST /api/cart/checkout`

### 订单

以下接口需要请求头 `X-Session-ID`：

- `GET /api/orders?pageNum=1&pageSize=10`
- `GET /api/orders/{id}`

### 运维

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics`

## 示例流程

```bash
curl -X POST http://localhost:8080/api/users/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"john_doe\",\"email\":\"john@example.com\",\"password\":\"password123\"}"

curl -X POST http://localhost:8080/api/users/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"john_doe\",\"password\":\"password123\"}"

curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Redis Mug\",\"description\":\"A mug for cache lovers\",\"price\":39.90,\"stock\":50,\"category\":\"daily\"}"
```

登录成功后，把返回的 `sessionId` 放入 `X-Session-ID` 请求头即可访问购物车和订单接口。

## Redis Key 设计

| Key | 说明 | TTL |
| --- | --- | --- |
| `product:{id}` | 商品详情缓存 | 1 小时 |
| `all:products` | 商品列表缓存 | 1 小时 |
| `hot:products` | 热门商品缓存 | 30 分钟 |
| `cart:{userId}` | 用户购物车 | 24 小时 |
| `session:{sessionId}` | 用户会话 | 24 小时 |
| `user:{id}` | 用户详情缓存 | 24 小时 |
| `user:username:{name}` | 用户名查询缓存 | 24 小时 |

## 测试

```bash
mvn test
```

当前测试覆盖用户服务、商品服务、购物车结算与库存扣减等核心逻辑。
