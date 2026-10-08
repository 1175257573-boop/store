# 商家体系升级 + 买卖双方 IM 设计方案

> 三块需求：①新增 2 个可登录商家并迁移存量商品归属 ②商品/店铺/订单全链路按店铺维度展示筛选 ③买家↔商家一对一会话
>
> 依据：基于**现有代码与数据库实测**的现状分析，不是通用设计。

---

## 一、现状盘点（实测结论）

### 已有的基础设施（比预期完整）

| 资产 | 现状 |
|---|---|
| `t_merchant` | 已有完整字段：user_id、shop_name、license_no、total_product、score |
| 角色体系 | `t_user.role`：0=买家 / 1=商家 / 2=管理员，已有 3024/2/1 人 |
| `t_merchant_apply` | 商家入驻申请流已实现（申请→审核→开店） |
| 商家端接口 | 已有 20+ 接口（商品增删改查、订单发货、售后、审核） |
| `t_order.merchant_id` | **订单表已有商家字段** |
| `t_order_item.merchant_id` | **订单项已有商家字段** |

### 缺口（本需求要补的）

| 缺口 | 影响 |
|---|---|
| `ProductVO` 无 `merchantId`/`shopName` | **列表和详情都无法按店铺筛选**，这是最大缺口 |
| 无店铺主页（`/shop/{merchantId}`） | 买家无法浏览某店铺的商品 |
| 无任何 IM 表 | 聊天功能从零开始 |
| 149 个商品 `merchant_id = NULL` | 需要迁移 |
| 商品列表无店铺筛选参数 | 需加 `merchantId` 入参 |

---

## 二、数据模型调整方案

### 2.1 商品表：merchant_id 改为必填

```sql
-- 存量先补默认值，再改约束。直接改 NOT NULL 会因 149 条 NULL 而失败
ALTER TABLE t_product
  MODIFY COLUMN merchant_id BIGINT NOT NULL COMMENT '所属商家ID';

-- 加索引：商品列表按店铺筛选是高频查询
ALTER TABLE t_product ADD INDEX idx_merchant_status (merchant_id, status);
```

**为什么必须 NOT NULL**：`merchant_id` 可空意味着"平台自营商品"，
这与本项目"每件商品必有归属店铺"的业务前提冲突。
留 NULL 会让筛选逻辑到处判空，且客服/订单统计都要特殊分支。

### 2.2 新增：买家-店铺会话表

```sql
CREATE TABLE t_im_session (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  -- 唯一约束：一对买家 × 一店铺只有一条会话（复用历史消息）
  buyer_id      BIGINT      NOT NULL COMMENT '买家用户ID',
  merchant_id   BIGINT      NOT NULL COMMENT '店铺ID',
  product_id    BIGINT               DEFAULT NULL COMMENT '关联商品（从商品页发起时带）',
  status        TINYINT     NOT NULL DEFAULT 1 COMMENT '1正常 2买家删除 3商家删除',
  buyer_unread  INT         NOT NULL DEFAULT 0 COMMENT '买家未读数',
  merchant_unread INT       NOT NULL DEFAULT 0 COMMENT '商家未读数',
  last_message  VARCHAR(500)         DEFAULT NULL COMMENT '最后一条消息摘要（列表展示）',
  last_time     DATETIME             DEFAULT NULL COMMENT '最后消息时间（排序用）',
  create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_buyer_merchant (buyer_id, merchant_id),
  KEY idx_buyer_time (buyer_id, last_time DESC),
  KEY idx_merchant_unread (merchant_id, merchant_unread)
) COMMENT='买家-店铺会话';
```

**设计要点**：

| 设计 | 理由 |
|---|---|
| `uk_buyer_merchant` 唯一键 | 一对一会话不重复建。买家和同一店铺再次咨询时**复用原会话**，否则历史消息就断了 |
| 双侧未读数分开 | 买家看自己的未读、商家看自己的未读，方向不同不能用同一个字段 |
| `last_message` + `last_time` | 会话列表要展示摘要并按时间排序，避免每行都 join 消息表 |
| `merchant_id` 而非 `product_id` | 会话归属**店铺**而非商品。买家问"这能退吗"应能延续到"那台呢" |

### 2.3 新增：消息表

```sql
CREATE TABLE t_im_message (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT      NOT NULL COMMENT '会话ID',
  from_id    BIGINT      NOT NULL COMMENT '发送者ID',
  from_role  TINYINT     NOT NULL COMMENT '0买家 1商家（冗余，省一次查表）',
  to_id      BIGINT      NOT NULL COMMENT '接收者ID',
  content    VARCHAR(1000) NOT NULL COMMENT '消息内容',
  read_flag  TINYINT     NOT NULL DEFAULT 0 COMMENT '1已读',
  create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_session_time (session_id, create_time),
  KEY idx_to_read (to_id, read_flag)
) COMMENT='聊天消息';
```

**关键索引**：`idx_session_time` 支撑按会话翻消息；
`idx_to_read` 支撑"我的未读"批量标记已读。

### 2.4 关联关系图

```
t_user (role=0 买家) ──1:N──> t_im_session <──N:1── t_merchant (role=1 商家)
                              │
                              │ 1:N
                              ↓
                        t_im_message

t_merchant ──1:N──> t_product ──1:N──> t_product_sku
      │                                    (merchant_id 冗余)
      └──1:N──> t_order ──1:N──> t_order_item
                   (已有 merchant_id)         (已有 merchant_id)
```

---

## 三、存量商品迁移方案

### 3.1 分配规则：**按分类均分 + 同分类内轮询均衡**

用户给了两个选项（均分/指定比例），我选**按分类均分**并说明理由：

| 规则 | 优点 | 缺点 |
|---|---|---|
| **按分类均分** | 每个店铺品类齐全，买家在任一店铺都能买到手机/家电/图书 | 单店商品数可能差 1-2 个 |
| 按比例指定 | 完全可控 | 不均衡时某店缺某些品类 |

**实现**：用 `ROW_NUMBER()` 按分类内排序轮转分配，保证均衡且确定。

```sql
-- 每个分类内按商品 ID 排序，轮流分给两个店铺
WITH ranked AS (
  SELECT id, category_id,
         ROW_NUMBER() OVER (PARTITION BY category_id ORDER BY id) AS rn
  FROM t_product
  WHERE merchant_id IS NULL          -- 只处理待迁移的
)
UPDATE t_product p
JOIN ranked r ON p.id = r.id
SET p.merchant_id = CASE WHEN (r.rn - 1) % 2 = 0 THEN :merchantA ELSE :merchantB END;
```

**为什么用 `ROW_NUMBER` 而不是简单 `id % 2`**：
按分类内轮转能保证**同一分类的连续商品不会全落一家**，
而 `id % 2` 会因分类起始 ID 的奇偶性产生系统性偏差。

### 3.2 迁移脚本必须做的事

| 步骤 | 说明 |
|---|---|
| 备份 | `mysqldump` 迁移前的 `t_product` |
| 预演 | 先 `SELECT` 看分配结果，确认合理再 `UPDATE` |
| 事务 | `START TRANSACTION` … `COMMIT`，失败可回滚 |
| 同步 SKU | `t_product_sku.merchant_id` 冗余了商品归属，**必须同步更新**，否则商家端按 SKU 查会漏 |
| 刷新统计 | `t_merchant.total_product` 重新计算 |
| 清缓存 | Redis 里商品详情缓存了旧数据，不清会显示错店铺 |

**SKU 的 merchant_id 是最容易漏的一步** —— 它是冗余字段，
不与商品同步就会出现"商品属于 A 店但 SKU 显示 B 店"的分裂。

### 3.3 订单归属回填（兼容性关键）

已存在的订单（含 `merchant_id = NULL` 的）需要回填到新店铺，
否则历史订单在商家端查不到。

```sql
-- 从订单项的商品归属推导订单归属（一单可能多商家，取首个）
UPDATE t_order o
JOIN (SELECT order_id, MIN(merchant_id) AS mid
      FROM t_order_item WHERE merchant_id IS NOT NULL
      GROUP BY order_id) x ON x.order_id = o.id
SET o.merchant_id = x.mid
WHERE o.merchant_id IS NULL;
```

**注意**：这是**不可逆的历史决策**。迁移后订单归属永久锁定，
不应随商品归属变化而改变 —— 否则商家 A 会被算上商家 B 的业绩。

---

## 四、商品/店铺/订单的店铺维度改造

### 4.1 VO 层：加字段（当前最大缺口）

```java
// ProductVO 新增
private Long merchantId;
private String shopName;
private String shopLogo;
```

**必须改的地方**：
- 商品列表 `ProductVO`
- 商品详情 `ProductDetailVO`
- 商家端商品列表（已有 merchantId 过滤，但 VO 要回显店铺名）

### 4.2 查询层：商品列表加店铺筛选

```
GET /api/product/list?merchantId=61&pageNum=1&pageSize=12
```

Service 层逻辑：
```java
if (merchantId != null) {
    wrapper.eq(Product::getMerchantId, merchantId);
}
```

### 4.3 新增：店铺主页接口

```
GET  /api/shop/{merchantId}              店铺信息
GET  /api/shop/{merchantId}/products     店铺的商品（分页，复用商品列表逻辑）
```

### 4.4 缓存策略（必须注意）

商品详情有 Redis 缓存（`ec:product:detail:{id}`）。
**新增 `merchantId` 字段后，缓存里是旧结构的 JSON**，
反序列化不会报错但字段为 null —— 表现为"店铺名空白"。

**处理**：改 VO 后必须清一次商品缓存；且**缓存 TTL 内不要改 VO 结构**。

---

## 五、聊天功能前后端交互流程

### 5.1 入口（三个位置，按用户要求）

| 位置 | 行为 |
|---|---|
| 商品详情页 | 「联系商家」→ 携带 `productId` 发起会话 |
| 店铺主页 | 「联系店铺」→ 携带 `merchantId` |
| 订单页 | 「联系商家」→ 携带 `orderId`，便于售后沟通 |

### 5.2 交互时序

```
买家点击「联系商家」
    │
    ├─→ POST /api/im/session  {merchantId, productId}
    │       服务端查 uk_buyer_merchant：
    │       ├─ 已存在 → 返回原 sessionId（历史消息继续）
    │       └─ 不存在 → 新建，返回新 sessionId
    │
    ├─→ GET /api/im/message?sessionId=xxx&pageNum=1
    │       拉历史消息 + 标记对方消息已读
    │
    └─→ POST /api/im/message {sessionId, content}
            发送 → 更新会话摘要/时间/未读数
```

### 5.3 会话列表

```
GET /api/im/sessions?pageNum=1&pageSize=20
```

**买家看到的**：按 `last_time` 倒序，附未读数徽标。
**商家看到的**：`WHERE merchant_id = 我的店铺`，同样按时间倒序。

### 5.4 实时消息实现思路

项目已有 **Redis**（用于缓存与限流），但**没有消息队列**。三档方案：

| 方案 | 实现 | 优点 | 缺点 | 适用 |
|---|---|---|---|---|
| **A. 轮询**（推荐先做） | 前端 `setInterval` 每 3-5s 拉增量 | 零依赖，当天可用 | 有延迟，N 个连接 = N×QPS | 本项目 2 核服务器 |
| B. SSE（Server-Sent Events） | 后端 `SseEmitter` 长连接 | 单向推送足够，Spring 原生支持 | 需要连接池管理 | 中等规模 |
| C. WebSocket + Redis Pub/Sub | 双向长连接 | 实时性最好 | **2 核机器压力大**，多实例需广播 | 大规模 |

**我的建议：先做 A（轮询），预留 B 接口。**

理由：**你的服务器是 2 核**，WebSocket 长连接 + 多实例广播会明显增加内存与 CPU 占用，
而轮询在客服场景（QPS 很低）完全够用。3-5 秒延迟用户无感。

轮询的实现要点：
```javascript
// 增量拉取：只取 afterId 之后的消息，不重复传输
async function poll(sessionId, afterId) {
  const res = await getMessages(sessionId, { afterId })
  if (res.data.length) { appendMessages(res.data); afterId = lastId(res.data) }
}
```
**必须用 `afterId` 增量拉取**，不能用"拉全部再对比"——消息多了会浪费带宽。

---

## 六、权限控制

### 6.1 三种角色的可见范围

| 操作 | 买家 | 商家 | 管理员 |
|---|---|---|---|
| 看商品列表/详情 | ✅ 全部在售 | ✅ 全部（可选只看本店） | ✅ 全部 |
| 按店铺筛选商品 | ✅ | ✅ | ✅ |
| 店铺主页 | ✅ | ✅ | ✅ |
| 发起会话 | ✅ | ❌ | ❌ |
| **看会话列表** | ✅ 仅自己有会话的 | ✅ **仅 merchant_id = 自己** | ✅ **全部**（后台排查用） |
| 发消息 | ✅ 仅自己参与的会话 | ✅ 仅自己店铺的会话 | ❌（管理员只读，不介入交易沟通） |
| 商品归属调整 | ❌ | ❌ | ✅ |

### 6.2 三个必须防的越权点

| 越权 | 后果 | 防护 |
|---|---|---|
| **买家传别人的 sessionId** | 看到他人对话 | 每个接口都校验 `session` 里有当前用户的 participant |
| **商家传别人的 merchantId** | 看到别家买家会话 | 会话查询强制 `WHERE merchant_id = 当前用户店铺` |
| **商家改不属于自己店的商品** | 越权操作 | 商品更新时校验 `product.merchant_id == 当前店铺` |

**核心原则**：会话接口的所有查询条件都必须**强制拼接当前身份**，
不能信任前端传来的 `merchantId`/`sessionId` 参数。

### 6.3 管理员为什么只读不介入

管理员能看到所有会话（排查纠纷），但**不应能以任何身份发消息** ——
管理员介入买卖双方沟通会破坏平台的"中立第三方"定位，
也让纠纷举证变得复杂。

---

## 七、兼容影响分析

| 影响点 | 风险 | 处理 |
|---|---|---|
| `merchant_id` 改 NOT NULL | 现有代码有 `merchant_id = NULL` 的分支 | 先迁移数据再改约束；商品创建时必填 |
| **ProductVO 加字段** | **Redis 旧缓存反序列化后字段为 null** | **改 VO 后必须清商品缓存** |
| 商品列表加 `merchantId` 参数 | 无（新增可选参数，向后兼容） | — |
| 订单归属回填 | **不可逆**，历史订单永久锁定 | 一次性执行并备份，确认后再跑 |
| 商家端商品列表 | 已有按 merchantId 过滤，改 VO 后需回显店铺名 | 商家端接口已存在，主要改 VO |
| 下单流程 | 订单/订单项已有 `merchant_id`，只需确保下单时正确赋值 | 需检查创建订单处是否已填 |
| 秒杀流程 | 秒杀商品也归属商家，需确认预扣逻辑不依赖 merchant_id | 需回归秒杀测试 |

**最大的兼容风险是 Redis 缓存** —— 改了 VO 但没清缓存，
会表现为"部分商品店铺名空白"，且不会报错，极难排查。

---

## 八、实施顺序建议

| 阶段 | 内容 | 验收 |
|---|---|---|
| **1** | 建两个商家账号 + 店铺主体 | 能登录、能看到店铺信息 |
| **2** | 迁移存量商品（含 SKU + 订单回填） | 商品列表按店铺筛选正确 |
| **3** | VO 加字段 + 商品列表/详情按店铺筛选 | 前端能按店铺筛 |
| **4** | 店铺主页接口 + 前端页面 | 能浏览某店全部商品 |
| **5** | IM 表结构 + 会话创建/列表/发消息接口 | 接口层能收发 |
| **6** | 前端聊天组件 + 三处入口 + 未读徽标 | 能完整对话 |
| **7** | 权限控制测试（三个角色的越权用例） | 越权全部被拒 |

**阶段 1-4 是店铺维度，5-7 是 IM**，两者除"都要按 merchant_id 过滤"外无耦合，
可以并行推进。

---

## 九、需要你确认的点

| 事项 | 我的建议 | 影响 |
|---|---|---|
| 商品分配规则 | 按分类均分 | 若要指定比例，脚本改一处即可 |
| 两个店铺的定位 | **数码优选 / 生活优选**（数码类偏多、生活类偏多） | 若要均衡分配则改为纯轮询 |
| 实时消息方案 | **先轮询**（2 核服务器友好） | 若要多实例部署，需上 WebSocket |
| 商家能否主动开场 | 建议**买家发起**，商家只能回复 | 否则商家骚扰买家 |
| 聊天内容审核 | 演示项目暂不做，但留敏感词表 | 生产必须有 |

**特别说明第 2 条**：如果两个店铺定位是"数码为主 / 生活为主"，
那分配规则就应该**按分类定向**（手机电脑→数码店，食品家居→生活店），
而不是均分。这会让店铺特色更鲜明。我倾向这个方案。