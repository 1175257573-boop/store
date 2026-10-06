# 优选商城 · 全栈电商平台

基于 **Java 17 + Spring Boot 3 + MyBatis-Plus + Redis + JWT** 的后端，与
**Vue 3 + Vite + Element Plus** 的前端，实现从注册登录、商品浏览、购物车到下单支付的完整链路。

包含三大业务模块：**用户端电商**、**商家中心**（入驻/商品/订单/售后/看板）、
**秒杀系统**（限流/双层校验防超卖/消费者集群/降级预案/对账补偿）。

---

## 快速开始

### 1. 环境要求

JDK 17+、Maven 3.9+、MySQL 5.7+、Redis 7+、Node.js 18+

### 2. 配置数据库连接

仓库不包含真实凭据，通过环境变量注入（参考 `.env.example`）：

```bash
export DB_PASSWORD=你的MySQL密码
export ECOMMERCE_JWT_SECRET=随机密钥
```

Windows 下用 `set` 命令，或在 IDEA 的运行配置里加环境变量。

### 3. 初始化数据库

```bash
# 先执行建库 + 用户端表
mysql -uroot -p --default-character-set=utf8mb4 < sql/ecommerce.sql
# 再执行秒杀表
mysql -uroot -p --default-character-set=utf8mb4 < sql/seckill.sql
# 商家端表
mysql -uroot -p --default-character-set=utf8mb4 < sql/merchant.sql
```

> **必须加 `--default-character-set=utf8mb4`**。MySQL 5.7 客户端在中文 Windows 下
> 默认按 GBK 解析 SQL 文件，会导致 `Data too long for column` 错误。详见
> [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。

### 4. 启动

```bash
# Redis
redis-server --port 6379

# 后端
cd backend && mvn spring-boot:run

# 前端（另开终端）
cd frontend && npm install && npm run dev
```

访问 <http://localhost:5173>，接口文档 <http://localhost:8080/doc.html>

### 5. 演示账号

| 账号 | 密码 | 角色 |
|---|---|---|
| `demo` | 123456 | 普通用户 |
| `admin` | 123456 | 平台管理员（入驻审核、商品审核、店铺管控） |
| `shop_a` | 123456 | 商家（优品旗舰店） |
| `shop_b` | 123456 | 商家（数码专营店） |

---

## 自动化测试

项目自带端到端测试脚本，全部基于**真实接口返回**断言，不含模拟数据。

```bash
python scripts/seed_stress_users.py 400   # 准备压测用户（可选）

python scripts/e2e_test.py                # 用户端 61 项
python scripts/merchant_e2e_test.py       # 商家端 60 项
python scripts/seckill_stress.py --concurrency 300 --stock 100   # 秒杀防超卖 5 项判据
python scripts/seckill_compensate_test.py # 补偿与幂等 16 项
python scripts/seckill_production_test.py # 限流/集群/死信 18 项
python scripts/seckill_degrade_test.py    # 降级预案 11 项
python scripts/verify_admin_menu.py       # 管理端菜单 25 项
python scripts/verify_admin_todo.py       # 审核红点 18 项
python scripts/verify_entry_paths.py      # 入口路径 19 项
```

秒杀压测的 5 条硬性判据（不通过即判定超卖）：

1. 成功订单数 ≤ 总库存
2. DB 剩余库存 ≥ 0
3. 总量守恒：可用 + 锁定 + 已售 = 总库存
4. 无重复订单
5. Redis 预扣净量 = 成功订单数

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [README.md](README.md) | 技术栈、接口清单、关键设计（本文） |
| [秒杀模块实现说明.md](秒杀模块实现说明.md) | 防超卖方案原理与压测数据 |
| [秒杀生产化能力说明.md](秒杀生产化能力说明.md) | 限流、消费者集群、降级预案、监控告警 |
| [商家端实现说明.md](商家端实现说明.md) | 角色权限模型、多规格 SKU、越权防护 |
| [联调报告.md](联调报告.md) | 各模块实测结果与修复的 Bug |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 环境搭建与排障踩坑记录 |

---

## 一、技术栈

| 层次 | 选型 | 说明 |
|---|---|---|
| 语言/运行时 | Java 17 (Temurin) | Records、switch 表达式、文本块 |
| 框架 | Spring Boot 3.2.5 | Web / Validation / Actuator |
| 持久层 | MyBatis-Plus 3.5.7 | 单表 CRUD 零 SQL，多表场景手写 SQL |
| 数据库 | MySQL 5.7 | InnoDB + utf8mb4 |
| 缓存 | Redis 7.2 | 商品详情/分类缓存、JWT 白名单 |
| 认证 | JJWT 0.12.5 | 签名校验 + Redis 白名单双保险 |
| 密码 | Spring Security Crypto | 仅用 BCrypt，不启用 Security 过滤器链 |
| 接口文档 | Knife4j 4.5 | Swagger 增强，中文界面 |
| 前端框架 | Vue 3.5 | `<script setup>` 组合式 API |
| 构建 | Vite 5 | 开发期代理 `/api` 到 8080 |
| UI | Element Plus 2.8 | 中文本地化 |
| 状态/路由 | Pinia 2 + Vue Router 4 | hash 模式 + 路由守卫 |

---

## 二、项目结构

```
ecommerce/
├── backend/                          # 后端 Maven 多模块
│   ├── pom.xml                       # 父 POM：统一依赖版本与编译配置
│   ├── ecommerce-common/             # 通用层
│   │   └── com/ecommerce/common/
│   │       ├── result/               #   Result 统一响应、ResultCode 状态码枚举
│   │       ├── exception/            #   BusinessException 业务异常
│   │       ├── context/              #   LoginUser、UserContextHolder 上下文
│   │       ├── constant/             #   RedisKey、BizConst 常量
│   │       └── util/                 #   BizUtil 工具
│   ├── ecommerce-dao/                # 数据访问层
│   │   └── com/ecommerce/dao/
│   │       ├── entity/               #   与表一一对应的实体
│   │       └── mapper/               #   Mapper 接口（继承 BaseMapper）
│   ├── ecommerce-service/            # 业务层
│   │   └── com/ecommerce/service/
│   │       ├── UserService ...       #   业务接口
│   │       ├── impl/                 #   业务实现（核心逻辑）
│   │       ├── dto/                  #   入参对象（带校验注解）
│   │       ├── vo/                   #   出参对象（裁剪 + 补充）
│   │       └── util/                 #   JwtUtil、RedisCacheUtil
│   └── ecommerce-api/                # 接口层（可执行模块）
│       └── com/ecommerce/api/
│           ├── EcommerceApplication  #   启动类
│           ├── controller/           #   REST 控制器
│           ├── config/               #   配置：Redis / Jackson / MyBatis / CORS / 拦截器
│           ├── interceptor/          #   JWT 认证拦截器
│           ├── exception/            #   全局异常处理
│           └── OpenApiConfig         #   接口文档元信息
├── frontend/                         # 前端 Vite 工程
│   └── src/
│       ├── api/index.js              #   全部接口封装
│       ├── utils/request.js          #   Axios 封装（令牌注入 + 响应拆包）
│       ├── stores/user.js            #   Pinia 用户状态
│       ├── router/index.js           #   路由 + 登录守卫
│       ├── layout/MainLayout.vue     #   顶栏 + 内容区 + 页脚
│       ├── views/                    #   9 个页面组件
│       └── styles/main.css           #   全局样式与 CSS 变量
└── sql/ecommerce.sql                 # 建表脚本 + 种子数据
```

### 分层职责

| 层 | 职责 | 约束 |
|---|---|---|
| **controller** | 接收请求、参数校验、调用 service、包装 `Result` | 不写业务逻辑，不直接碰 Mapper |
| **service** | 业务规则、事务边界、缓存策略 | 可调多个 Mapper，不感知 HTTP |
| **mapper** | 数据访问 | 只有需要手写 SQL 的方法才显式声明 |
| **entity** | 表映射 | 不承载业务逻辑 |

---

## 三、快速启动

### 1. 环境准备

| 组件 | 版本 | 本项目安装位置 |
|---|---|---|
| JDK | 17+ | `E:\devtools\jdk17` |
| Maven | 3.9+ | `E:\devtools\apache-maven-3.9.9` |
| Redis | 7.x | `E:\devtools\redis\Redis-7.2.5-Windows-x64-msys2` |
| MySQL | 5.7+ | 本机 3306 |
| Node.js | 18+ | 本机 v22 |

### 2. 初始化数据库

```bash
mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/ecommerce.sql
```

> **必须加 `--default-character-set=utf8mb4`**。MySQL 5.7 客户端默认按本地编码（GBK）
> 解析 SQL 文件，会把中文按双字节处理，导致 `VARCHAR` 列写入报
> `Data too long for column`。这个坑在 Windows 中文环境下必现。

### 3. 启动 Redis

```bash
redis-server --port 6379
```

### 4. 启动后端

```bash
cd backend
mvn spring-boot:run
# 或打包后运行
mvn clean package -DskipTests
java -jar ecommerce-api/target/ecommerce-api.jar
```

- 服务地址：`http://localhost:8080`
- 接口文档：`http://localhost:8080/doc.html`

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

- 访问地址：`http://localhost:5173`

### 演示账号

| 用户名 | 密码 | 说明 |
|---|---|---|
| `demo` | `123456` | 演示用户，已有默认收货地址 |
| `admin` | `123456` | 管理员 |

---

## 四、接口清单

所有接口前缀 `/api`，除标注「公开」外均需携带请求头 `Authorization: Bearer <token>`。

### 用户 `/api/user`

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/register` | 注册 | 公开 |
| POST | `/login` | 登录，返回 JWT | 公开 |
| GET | `/profile` | 当前用户信息 | 需登录 |
| PUT | `/profile` | 修改资料 | 需登录 |
| PUT | `/password` | 修改密码 | 需登录 |

### 商品 `/api/product`

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/list` | 分页查询（分类/关键字/排序） | 公开 |
| GET | `/{id}` | 商品详情，浏览量 +1 | 公开 |
| GET | `/{id}/related` | 相关推荐 | 公开 |
| GET | `/categories` | 分类列表 | 公开 |

### 购物车 `/api/cart`（全部需登录）

`GET /`、`POST /`、`PUT /{id}`（改数量）、`PUT /{id}/checked`、`PUT /checked-all`、
`DELETE /{id}`、`DELETE /batch`、`GET /count`

### 地址 `/api/address`（全部需登录）

`GET /`、`GET /default`、`POST /`、`PUT /{id}`、`DELETE /{id}`、`PUT /{id}/default`

### 订单 `/api/order`（全部需登录）

`POST /`（下单）、`GET /list`、`GET /{id}`、`POST /{id}/cancel`、
`POST /{id}/pay`、`POST /{id}/confirm`、`GET /count`

---

## 五、关键设计说明

### 1. 统一响应结构

所有接口返回：

```json
{ "code": 200, "message": "操作成功", "data": {} }
```

HTTP 状态码**始终为 200**，成败由业务码 `code` 表达。这样前端 axios 拦截器
只需处理一条分支，不会因 4xx/5xx 走 `error` 分支而漏掉统一提示。

### 2. JWT + Redis 白名单

纯无状态 JWT 的固有缺陷是：**用户登出后，旧令牌在有效期内仍能继续使用**。

本项目的处理是双保险：

```
签发令牌 → 签名 + payload 写入 JWT，同时在 Redis 存一份白名单（TTL = 令牌有效期）
校验令牌 → 验签 + 验有效期（jjwt 负责）
         → 再查 Redis 白名单，key 不存在则判定为已登出
```

登出即删白名单 key，令牌立即失效。

### 3. 防超卖：不走 Redis 预扣，用条件更新

常见方案是「Redis 预扣库存 → 异步落库」，但存在超卖窗口：预扣成功后订单落库失败，
库存就永久少卖了。

本项目改为在**同一事务内**用带条件的原子更新：

```sql
UPDATE t_product SET stock = stock - #{quantity}, sales = sales + #{quantity}
WHERE id = #{id} AND stock >= #{quantity}
```

- 影响行数 = 0 → 库存已被并发请求抢空 → 抛异常
- 异常触发事务回滚，**之前所有已扣减的库存一并还回去**
- 不存在「预扣了但没卖出去」的中间态

### 4. 订单快照

`order` 与 `order_item` 中的收货人、电话、地址、商品名、图片、成交单价，
全部是**下单时刻的快照**。用户日后修改地址或商品调价，历史订单展示不受影响。

### 5. Long 型 ID 序列化为字符串

JS 的 `Number` 最大安全整数是 `2^53 - 1`。ID 一旦超出该范围，
前端拿到的数字会丢精度（末位变成 0）。全局配置 Jackson 将 `Long` 序列化为字符串。

### 6. 登录上下文用 ThreadLocal 传递

```
JWT 拦截器解析令牌 → 写入 UserContextHolder（ThreadLocal）
    ↓
Service 层 UserContextHolder.requireUserId() 直接取，不层层传 userId
    ↓
afterCompletion 清理 ThreadLocal
```

> 清理这一步是必须的：Tomcat 线程会被复用，残留的上下文会导致下一个请求
> 拿到上一个用户的身份，属于越权风险。

### 7. DTO 而非实体作为入参

`updateProfile` 接收的是 `ProfileUpdateDTO` 而非 `User` 实体。实体里含
`password`、`status`、`username` 等字段，直接接收实体等于把提权口子交给前端。

### 8. 越权防护

所有涉及用户私有数据的操作都带 `user_id` 条件：

```java
// 购物车条目
baseMapper.deleteByIdAndUserId(cartId, userId);
// 订单
getOne(lambdaQuery().eq(Order::getId, orderId).eq(Order::getUserId, userId));
// 地址
baseMapper.selectOne(lambdaQuery().eq(Address::getId, id).eq(Address::getUserId, userId));
```

### 9. 缓存策略

| 数据 | 是否缓存 | 理由 |
|---|---|---|
| 商品分类 | 是，1 天 | 数据几乎不变，读多写少 |
| 商品详情 | 是，30 分钟 | 读多写少；**浏览量不缓存**，每次真实自增 |
| 商品列表 | 否 | 组合条件多、命中率低，缓存 key 数量爆炸得不偿失 |
| 购物车 | 否（落库） | 需持久化，条数有限，单表查询足够 |

### 10. 前端登录态持久化

令牌与用户信息存 `localStorage`，刷新页面不掉登录态。
应用启动时用本地令牌调一次 `/profile` 拉取最新资料。
路由守卫拦截 `meta.requiresAuth` 的页面，未登录跳登录页并记录来源路径，
登录后跳回原目标页。

---

## 六、数据库表

| 表名 | 说明 | 关键设计 |
|---|---|---|
| `t_user` | 用户 | `username` 唯一索引 |
| `t_category` | 商品分类 | `sort_order` 排序 |
| `t_product` | 商品 | `category_id` + `status` 联合索引；`stock >= ?` 条件更新 |
| `t_cart_item` | 购物车 | `(user_id, product_id)` 唯一，保证同商品只有一条 |
| `t_address` | 收货地址 | `user_id` 索引 |
| `t_order` | 订单主表 | `order_no` 唯一；`(user_id, status)` 联合索引 |
| `t_order_item` | 订单明细 | `order_id` 索引；商品信息为快照 |

种子数据：2 个用户、6 个分类、15 个商品、1 个收货地址。

---

## 七、项目规模

| 类型 | 数量 |
|---|---|
| Java 源文件 | 61 个 |
| Java 代码行数 | 3660 行 |
| Vue 组件 | 13 个 |
| 前端代码行数 | 3733 行 |
| 数据库表 | 7 张 |
| 接口数量 | 30+ |

## 八、自动化测试

`scripts/e2e_test.py` 是端到端联调脚本，覆盖公开浏览、认证、购物车、越权防护、
收货地址、订单主链路、个人资料七大场景共 **61 项断言**。

```bash
# 需先启动 MySQL、Redis、后端服务
python scripts/e2e_test.py
```

覆盖的关键场景：

- **公开接口**：分类、商品列表（分页/分类筛选/关键字/价格排序）、详情、浏览量自增
- **认证**：登录成功、错误密码拒绝、不存在账号拒绝、未登录 401、无效令牌拦截、密码不泄漏
- **购物车**：加购、小计计算、重复加购累加、改数量、超库存拒绝、勾选计数
- **越权防护**：他人无法删除我的购物车条目、无法查看他人订单、资料接口无法改密码
- **订单**：金额核算、**库存精确扣减**、下单后清理购物车、重复支付拒绝、状态机校验、
  **取消订单库存精确回补**
- **地址**：新增、参数校验（非法手机号）、默认地址唯一性、删除

脚本可重复执行：会清理历史购物车、还原被修改的用户资料。

## 九、常见问题

**Q：MySQL 导入报 `Data too long for column`？**
A：客户端编码问题，加 `--default-character-set=utf8mb4`（见上文 3.2）。

**Q：`data too long` 出现在商品 `name`？**
A：同理。Windows 中文环境 MySQL 5.7 客户端默认 GBK。

**Q：启动报 `Unable to connect to Redis`？**
A：Redis 未启动，先 `redis-server --port 6379`。

**Q：前端请求 404 / 网络异常？**
A：后端未启动。Vite 代理把 `/api` 转发到 `localhost:8080`，
后端未起时前端页面能打开但接口全挂。

**Q：`ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`？**
A：在 Git Bash 下执行 `mvn` 脚本的路径问题。脚本用 `$JAVA_HOME/bin/java` 拼
POSIX 路径，Windows JVM 不认。改用 `E:\devtools\mvnw.sh`（已处理路径转换）。

**Q：启动报 `Invalid value type for attribute 'factoryBeanObjectType': java.lang.String`？**
A：用了 `mybatis-plus-boot-starter`（内置 mybatis-spring 2.x），它与 Spring Boot 3
不兼容。必须换成 `mybatis-plus-spring-boot3-starter`。本项目 POM 已按此配置。

**Q：`IService.count(Wrapper)` 或 `selectOne(lambdaQuery())` 报
`can not use this method for "getSqlFirst"`？**
A：根因是**无参 `lambdaQuery()`** —— 它靠 `ServiceImpl.currentModelClass()`
反射推断泛型，在本工程场景下拿不到实体类型，OGNL 求值 Wrapper 的 `sqlFirst`
属性失败。改用显式泛型即可：
```java
// 有问题
baseMapper.selectOne(lambdaQuery().eq(...))
// 正确
baseMapper.selectOne(Wrappers.<Address>lambdaQuery().eq(...))
```
本项目所有 Wrapper 创建均已统一为显式泛型形式。

**Q：Spring Security 默认拦截所有请求，前端全 401？**
A：只注册 `PasswordEncoder` Bean 而不写 `SecurityFilterChain`，Spring Boot 会
自动装配默认过滤器链（CSRF 校验 + 拦截全部 URL + 生成随机密码打日志）。
必须显式声明 `SecurityFilterChain` 并 `anyRequest().permitAll()`，
把鉴权交给自研的 JWT 拦截器。

**Q：Redis 启动报 `Can't set maximum open files`？**
A：Windows 版 Redis 常见告警，不影响功能，已自动下调 maxclients。
