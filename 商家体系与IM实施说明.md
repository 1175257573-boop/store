# 商家体系升级 + 买卖双方 IM 实施说明

> 配套设计方案：`商家体系与IM设计方案.md`
> 实测验证：`scripts/verify_merchant_im.py` → **21/21 通过**
> 回归：电商 61/61 · 商家端 60/60 · 秒杀 18/18 · 聊天 23/23

---

## 一、交付清单

| 类别 | 文件 | 说明 |
|---|---|---|
| 表结构 | `sql/im.sql` | `t_im_session` / `t_im_message` |
| 实体 | `ImSession` / `ImMessage` | — |
| Mapper | `ImSessionMapper` / `ImMessageMapper` | 权限条件写进 SQL |
| 服务 | `ImService` | 会话创建/列表/收发/权限 |
| 接口 | `ImController` | `/api/im/*` |
| 迁移 | `scripts/migrate_merchant_split.py` | 幂等，可重复执行 |
| 验证 | `scripts/verify_merchant_im.py` | 21 项，含 5 条越权用例 |

---

## 二、迁移结果

### 店铺账号

| 账号 | 密码 | 店铺 | user_id | merchant_id |
|---|---|---|---|---|
| `digital_shop` | shop123456 | 数码优选旗舰店 | 8249 | 62 |
| `life_shop` | shop123456 | 生活优选生活馆 | 8250 | 63 |

### 商品归属：分类内均分

```
手机通讯    共 15 条 → 数码  8 / 生活  7
电脑办公    共 16 条 → 数码  8 / 生活  8
家用电器    共 17 条 → 数码  9 / 生活  8
服饰鞋包    共 16 条 → 数码  8 / 生活  8
食品生鲜    共 14 条 → 数码  7 / 生活  7
图书文娱    共 15 条 → 数码  8 / 生活  7
家居家纺    共 40 条 → 数码 20 / 生活 20
办公文具    共 16 条 → 数码  8 / 生活  8
────────────────────────────────
合计              数码优选 76 件 / 生活优选 73 件
```

**为什么不是「整类定向」**：家居家纺有 40 件，其他分类才 14~17 件。
整类定向会导致 47 vs 102 的严重失衡。分类内均分后总量均衡，
且每个分类在两店都有 —— 买家在任一店铺都能找到手机/家电/图书。

### 同步修的三处冗余

| 项 | 问题 | 处理 |
|---|---|---|
| `t_product_sku.merchant_id` | **250 条 SKU 与商品归属不一致** | 已同步，现在 0 条不一致 |
| `t_order_item.merchant_id` | 73 条订单项为空 | 从商品回填 |
| `t_order.merchant_id` | 73 条订单为空 | 从订单项回填 |

**SKU 那一处最容易漏** —— 它是冗余字段，不与商品同步就会出现
「商品属于 A 店但 SKU 显示 B 店」的分裂。

---

## 三、迁移过程中踩的两个坑

### 1. MySQL 5.7 不支持窗口函数

方案文档里写的 `ROW_NUMBER() OVER (PARTITION BY ...)` 直接报：

```
ERROR 1064 ... near '(PARTITION BY category_id ORDER BY id)'
```

`ROW_NUMBER` 是 MySQL **8.0+** 的特性，5.7 没有。

改用**用户变量**模拟分组行号 —— 5.7 唯一可行的方案：

```sql
CREATE TABLE tmp_alloc_tmp AS
SELECT id AS pid,
  (@rn := IF(@cat = category_id, @rn + 1, 1)) AS rn,
  (@cat := category_id) AS _cat
FROM (SELECT id, category_id FROM t_product WHERE merchant_id IS NULL
      ORDER BY category_id, id) t,
     (SELECT @rn := 0, @cat := -1) vars;
```

关键：`ORDER BY category_id, id` 让同分类的行连续，变量才能正确重置。

### 2. 临时表跨连接消失

每次 `mysql -e` 是**独立连接**，`CREATE TEMPORARY TABLE` 建完下一条就没了
（报 `Table doesn't exist`）。改用真实表 + 用完即删。

---

## 四、权限控制（已实测 5 条越权用例）

### 角色可见范围

| 操作 | 买家 | 商家 | 管理员 |
|---|---|---|---|
| 发起会话 | ✅ | ❌ | ❌ |
| 看会话列表 | ✅ 仅自己参与的 | ✅ **仅 merchant_id = 自己** | ✅ 全部（只读） |
| 发消息 | ✅ | ✅ 仅本店会话 | ❌ |
| 未读红点 | ✅ 自己的 | ✅ 本店的 | — |
| 按店铺筛商品 | ✅ | ✅ | ✅ |

**管理员为什么只读**：介入买卖双方沟通会破坏平台的「中立第三方」定位，
也让纠纷举证变复杂。

### 五条越权用例全部被拒

| 用例 | 结果 |
|---|---|
| 商家B 访问商家A的会话 | ✅ 拒绝 |
| 商家B 回复商家A的会话 | ✅ 拒绝 |
| 商家主动发起会话（骚扰买家） | ✅ 拒绝 |
| 未登录访问会话列表 | ✅ 拒绝 |
| 商家B 的列表混入别家店铺 | ✅ 无混入 |

### 防护实现原则

**所有身份条件拼进 SQL，不只靠 service 层判断。**

```java
// 商家会话列表：merchantId 来自登录态，不接受前端传入
@Select("... WHERE s.merchant_id = #{merchantId} AND s.status != 3 ...")
List<ImSessionVO> listMerchantSessions(@Param("merchantId") Long merchantId, ...)
```

理由：service 层「先查再判断」是两步操作，中间有竞态。
把条件写进 SQL 让数据库保证，比应用层可靠。

**会话访问统一过 `requireSession(sessionId)`**：
买家校验 `buyer_id == 自己`，商家校验 `merchant_id == 自己店铺`，管理员放行只读。

---

## 五、实时消息：轮询

按你的选择用轮询（2 核服务器友好）。

### 接口

```
GET /api/im/message/poll?sessionId=xxx&afterId=123
```

**必须用 `afterId` 增量拉取**，不能"拉全量再对比"——消息多了浪费带宽。

### 前端要点

```javascript
// 增量拉取：只取 afterId 之后的新消息
async function poll(sessionId, afterId) {
  const res = await pollMessages(sessionId, { afterId })
  if (res.data.length) {
    appendMessages(res.data)
    afterId = res.data[res.data.length - 1].id   // 推进游标
  }
}
setInterval(() => poll(sessionId, afterId), 3000)  // 3 秒一轮
```

**接口设计已预留升级空间**：若将来要 SSE/WebSocket，只需在
`pollMessages` 旁边加一个长连接接口，前端的 `appendMessages` 逻辑不用改。

---

## 六、兼容影响

| 改动 | 影响面 | 处理 |
|---|---|---|
| `ProductVO` 加 2 字段 | **Redis 旧缓存反序列化后字段为 null** | 已清缓存；改 VO 后必须再清 |
| `pageProducts` 加参数 | 方法签名变更 | Controller 已同步；这是唯一的调用方 |
| `BizConst` 加常量 | 无 | 新增不改动既有值 |
| 商品归属迁移 | 秒杀商品也归属商家 | 秒杀 18/18 通过 |
| 历史订单归属 | **不可逆** | 一次性执行并备份过 |

### 最大风险仍是 Redis 缓存

改了 VO 但不清缓存，表现为**部分商品店铺名空白**，且不报错，极难排查。
迁移脚本末尾已提示必须清缓存。

---

## 七、遗留与后续

**未做**（需要你确认是否要做）：

| 事项 | 说明 |
|---|---|
| 店铺主页前端页面 | 接口侧已具备筛选能力，前端页面未做 |
| 商品详情页「联系商家」入口 | 后端接口就绪，前端入口未加 |
| 订单页「联系商家」入口 | 同上 |
| IM 轮询前端实现 | 接口就绪，前端未接 |
| 聊天内容审核 | 演示项目暂不做，生产必须有敏感词表 |

**已预留扩展点**：
- `t_im_session.status`（1正常/2买家删除/3商家删除）—— 单方删除不影响另一方
- `product_id` 字段 —— 从商品页发起时带入，便于后续做"关于某商品"的上下文
- 实时方案可从轮询平滑升级到 SSE

---

## 八、复现命令

```bash
# 1. 迁移（先预演，加 --execute 才写库）
python scripts/migrate_merchant_split.py
python scripts/migrate_merchant_split.py --execute

# 2. IM 建表
mysql -uroot -p123456 -e "USE ecommerce; SOURCE sql/im.sql;"

# 3. 打包（用安全脚本，它会校验 jar 完整性）
python scripts/build_backend.py

# 4. 启动后端
cd backend && DB_PASSWORD=123456 "E:/devtools/jdk17/bin/java.exe" \
  -jar ecommerce-api/target/ecommerce-api.jar

# 5. 验证
python scripts/verify_merchant_im.py     # 21 项，含越权用例
```

**测试账号**：

| 角色 | 账号 | 密码 |
|---|---|---|
| 商家A | digital_shop | shop123456 |
| 商家B | life_shop | shop123456 |
| 买家 | im_buyer | buyer123 |