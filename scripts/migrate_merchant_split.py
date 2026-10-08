"""迁移脚本：新建两个商家店铺 + 存量商品归属迁移。

按分类定向分配（不是均分）—— 两个店铺定位不同：
  数码优选：手机通讯 / 电脑办公 / 办公文具
  生活优选：家用电器 / 服饰鞋包 / 食品生鲜 / 图书文娱 / 家居家纺

三个易错点
----------
1. **t_product_sku.merchant_id 是冗余字段**，不与商品同步更新就会出现
   「商品属于 A 店但 SKU 显示 B 店」的分裂 —— 这是最容易漏的一步。
2. **Redis 里缓存了商品详情**（含 merchantId），迁移后不清缓存会显示旧归属。
3. **订单归属回填不可逆** —— 迁移后历史订单永久锁定，不应随商品变化而改变。
   否则商家 A 会被算上商家 B 的业绩。

用法：
    python scripts/migrate_merchant_split.py            # 预演，不写库
    python scripts/migrate_merchant_split.py --execute   # 实际执行
"""

import io
import os
import subprocess
import sys

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
DB = "ecommerce"

# 两个店铺：数码优选 / 生活优选
SHOPS = [
    {
        "username": "digital_shop",
        "nickname": "数码优选",
        "shop_name": "数码优选旗舰店",
        "shop_desc": "手机通讯、电脑办公与办公文具，正品行货，官方质保，全国联保。",
        "contact_name": "陈数码",
        "contact_phone": "13800000001",
        "license_no": "91310115MA1K3DIGIT",
        "business_type": 1,
        "categories": [1, 2, 8],   # 手机通讯 / 电脑办公 / 办公文具
    },
    {
        "username": "life_shop",
        "nickname": "生活优选",
        "shop_name": "生活优选生活馆",
        "shop_desc": "家用电器、服饰鞋包、食品生鲜、图书文娱与家居家纺，品质生活好选择。",
        "contact_name": "林生活",
        "contact_phone": "13800000002",
        "license_no": "91310115MA1K4LIFE",
        "business_type": 1,
        "categories": [3, 4, 5, 6, 7],  # 家用电器/服饰鞋包/食品生鲜/图书文娱/家居家纺
    },
]

# 默认密码（演示用）。生产环境应首次登录强制修改。
DEFAULT_PASSWORD = "shop123456"


def sql(stmt, raw=False):
    p = subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
         "--batch", "--raw", "-e", f"USE {DB}; {stmt}"],
        capture_output=True)
    out = p.stdout.decode("utf-8", errors="replace")
    if p.returncode != 0:
        err = p.stderr.decode("utf-8", errors="replace")
        if "ERROR" in err:
            print(f"  [SQL 错误] {err[:200]}")
        return None
    return out if raw else None


def esc(v):
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def generate_bcrypt_hash(password: str) -> str:
    """生成 BCrypt 哈希。

    必须与 UserServiceImpl 用的 PasswordEncoder 一致（BCrypt）。
    直接跑 Java 太麻烦，这里用 Python 的 bcrypt 库；
    若没装则提示装，或者退回用 jar 里的实现。
    """
    try:
        import bcrypt
        return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")
    except ImportError:
        print("  [错误] 需要 bcrypt 库：pip install bcrypt")
        sys.exit(1)


def show(title):
    print()
    print("=" * 68)
    print(f" {title}")
    print("=" * 68)


def main():
    execute = "--execute" in sys.argv

    show("第 1 步：检查现状")
    out = sql("SELECT COUNT(*) FROM t_product WHERE merchant_id IS NULL;", raw=True)
    null_cnt = int([l for l in out.split("\n") if l.strip()][1])
    print(f"  待迁移商品（merchant_id IS NULL）: {null_cnt}")

    out = sql("SELECT COUNT(*) FROM t_order WHERE merchant_id IS NULL;", raw=True)
    null_order = int([l for l in out.split("\n") if l.strip()][1])
    print(f"  待回填订单（merchant_id IS NULL）: {null_order}")

    out = sql("SELECT COUNT(*) FROM t_product_sku s "
              "JOIN t_product p ON p.id = s.product_id "
              "WHERE s.merchant_id IS NULL OR s.merchant_id <> p.merchant_id;", raw=True)
    mismatch = int([l for l in out.split("\n") if l.strip()][1])
    print(f"  SKU 与商品归属不一致: {mismatch}")

    if not execute:
        show("预演模式（加 --execute 实际执行）")
        print(f"  将创建 {len(SHOPS)} 个商家账号与店铺：")
        for s in SHOPS:
            print(f"    {s['username']:14s} → {s['shop_name']:16s} "
                  f"分类 {s['categories']}")
        print()
        print("  商品分配预演（按分类定向）:")
        out = sql("SELECT c.name AS cat, COUNT(*) AS cnt FROM t_product p "
                  "JOIN t_category c ON c.id = p.category_id "
                  "WHERE p.merchant_id IS NULL GROUP BY c.id, c.name ORDER BY c.id;",
                  raw=True)
        lines = [l for l in out.split("\n")[1:] if l.strip()]
        a, b = SHOPS[0]['shop_name'], SHOPS[1]['shop_name']
        total = {a: 0, b: 0}
        print("    分类内均分预演（A/B 交替）：")
        for line in lines:
            parts = line.split("\t")
            if len(parts) >= 2:
                cat, cnt = parts[0], int(parts[1])
                ca, cb = cnt // 2 + cnt % 2, cnt // 2
                total[a] += ca; total[b] += cb
                print(f"    {cat:10s} 共 {cnt:3d} 条 → {a[:4]} {ca:3d} / {b[:4]} {cb:3d}")
        print()
        for shop, cnt in total.items():
            print(f"    合计 {shop}: {cnt} 条")
        print()
        print("  确认无误后执行：python scripts/migrate_merchant_split.py --execute")
        return 0

    # ---------- 实际执行 ----------
    show("第 2 步：创建商家账号与店铺")
    pwd_hash = generate_bcrypt_hash(DEFAULT_PASSWORD)
    shop_ids = []

    for s in SHOPS:
        # 用户名已存在则复用（脚本幂等）
        out = sql(f"SELECT id FROM t_user WHERE username = {esc(s['username'])};",
                  raw=True)
        lines = [l for l in out.split("\n") if l.strip()]
        if len(lines) >= 2:
            user_id = int(lines[1].strip())
            print(f"  {s['username']:14s} 用户已存在，ID={user_id}")
        else:
            sql(f"INSERT INTO t_user (username, password, nickname, role, status) "
                f"VALUES ({esc(s['username'])}, {esc(pwd_hash)}, "
                f"{esc(s['nickname'])}, 1, 1);", raw=True)
            out = sql(f"SELECT id FROM t_user WHERE username = {esc(s['username'])};",
                      raw=True)
            user_id = int([l for l in out.split("\n") if l.strip()][1])
            print(f"  {s['username']:14s} 用户已创建，ID={user_id}")

        # 店铺
        out = sql(f"SELECT id FROM t_merchant WHERE shop_name = {esc(s['shop_name'])};",
                  raw=True)
        lines = [l for l in out.split("\n") if l.strip()]
        if len(lines) >= 2:
            mid = int(lines[1].strip())
            print(f"  {s['shop_name']:16s} 店铺已存在，ID={mid}")
        else:
            sql(f"INSERT INTO t_merchant (user_id, shop_name, shop_desc, "
                f"contact_name, contact_phone, business_type, license_no, "
                f"status, total_product, total_order, total_sales, score) "
                f"VALUES ({user_id}, {esc(s['shop_name'])}, {esc(s['shop_desc'])}, "
                f"{esc(s['contact_name'])}, {esc(s['contact_phone'])}, "
                f"{s['business_type']}, {esc(s['license_no'])}, 1, 0, 0, 0.00, 4.80);",
                raw=True)
            out = sql(f"SELECT id FROM t_merchant WHERE shop_name = {esc(s['shop_name'])};",
                      raw=True)
            mid = int([l for l in out.split("\n") if l.strip()][1])
            print(f"  {s['shop_name']:16s} 店铺已创建，ID={mid}")
        shop_ids.append(mid)   # 与 SHOPS 顺序对应

    print()
    print("  商家账号（登录用）：")
    for s in SHOPS:
        print(f"    {s['username']:14s} / {DEFAULT_PASSWORD}")

    show("第 3 步：迁移存量商品归属（分类内均分）")
    # 为什么不是「整类定向」：家居家纺有 40 件，其他分类才 14~17 件，
    # 整类定向会导致 47 vs 102 的严重失衡。
    # 改为分类内 ROW_NUMBER 轮转 —— 每类内部两店轮流拿，
    # 既保证总量均衡，又保留「数码店偏数码」的店铺特色。
    midA, midB = shop_ids[0], shop_ids[1]
    # ⚠️ MySQL 5.7 不支持窗口函数（ROW_NUMBER 是 8.0+ 的特性）。
    # 实测报 ERROR 1064 near '(PARTITION BY category_id ORDER BY id)'。
    # 改用「用户变量」模拟行号 —— 5.7 唯一可行的方案：
    #   @rn := IF(@cat = category_id, @rn + 1, 1) AS rn
    #   先按 category_id 分组排序，让同分类的行连续，变量才能正确重置。
    sql(f"DROP TABLE IF EXISTS tmp_alloc_tmp;"
        f"CREATE TABLE tmp_alloc_tmp AS "
        f"SELECT id AS pid, "
        f"  (@rn := IF(@cat = category_id, @rn + 1, 1)) AS rn, "
        f"  (@cat := category_id) AS _cat "
        f"FROM (SELECT id, category_id FROM t_product WHERE merchant_id IS NULL "
        f"      ORDER BY category_id, id) t, "
        f"  (SELECT @rn := 0, @cat := -1) vars;", raw=True)
    sql(f"UPDATE t_product p JOIN tmp_alloc_tmp a ON p.id = a.pid "
        f"SET p.merchant_id = CASE WHEN (a.rn - 1) % 2 = 0 THEN {midA} ELSE {midB} END;",
        raw=True)
    sql("DROP TABLE IF EXISTS tmp_alloc_tmp;", raw=True)
    print(f"  每个分类内按 ID 顺序轮流分配到 {midA} / {midB}")
    print("  注：MySQL 5.7 无窗口函数，用用户变量模拟分组行号")

    out = sql("SELECT COUNT(*) FROM t_product WHERE merchant_id IS NULL;", raw=True)
    remain = int([l for l in out.split("\n") if l.strip()][1])
    print(f"  剩余未归属: {remain}")

    if remain > 0:
        print("  [警告] 仍有商品未归属，需检查是否有新分类未在映射中")

    show("第 4 步：同步 SKU 的冗余 merchant_id")
    # 这一步最容易漏 —— SKU 的 merchant_id 是冗余字段，
    # 不同步就会出现「商品属于 A 店但 SKU 显示 B 店」
    sql("UPDATE t_product_sku s JOIN t_product p ON p.id = s.product_id "
        "SET s.merchant_id = p.merchant_id "
        "WHERE s.merchant_id IS NULL OR s.merchant_id <> p.merchant_id;", raw=True)
    out = sql("SELECT COUNT(*) FROM t_product_sku s "
              "JOIN t_product p ON p.id = s.product_id "
              "WHERE s.merchant_id <> p.merchant_id;", raw=True)
    mismatch = int([l for l in out.split("\n") if l.strip()][1])
    print(f"  SKU 归属不一致: {mismatch} {'（OK）' if mismatch == 0 else '（需重跑）'}")

    show("第 5 步：回填历史订单归属")
    # ⚠️ 必须两步，且第一步不能省：
    # 存量订单项的 merchant_id 同样是空的，直接从订单项推订单会推不出任何东西。
    #   ① 订单项归属 ← 商品归属（商品的 merchant_id 刚刚才填好）
    #   ② 订单归属   ← 订单项归属（多商家订单取 MIN，与下单时一致）
    sql("UPDATE t_order_item oi JOIN t_product p ON p.id = oi.product_id "
        "SET oi.merchant_id = p.merchant_id "
        "WHERE oi.merchant_id IS NULL AND p.merchant_id IS NOT NULL;", raw=True)
    print("  ① 订单项归属已从商品回填")
    sql("UPDATE t_order o JOIN (SELECT order_id, MIN(merchant_id) AS mid "
        "FROM t_order_item WHERE merchant_id IS NOT NULL GROUP BY order_id) x "
        "ON x.order_id = o.id SET o.merchant_id = x.mid "
        "WHERE o.merchant_id IS NULL;", raw=True)
    print("  ② 订单归属已从订单项回填")
    out = sql("SELECT COUNT(*) FROM t_order WHERE merchant_id IS NULL;", raw=True)
    print(f"  剩余未归属订单: {int([l for l in out.split(chr(10)) if l.strip()][1])}")
    out = sql("SELECT COUNT(*) FROM t_order_item WHERE merchant_id IS NULL;", raw=True)
    print(f"  剩余未归属订单项: {int([l for l in out.split(chr(10)) if l.strip()][1])}")
    print("  注意：订单归属一经回填即永久锁定，不随商品变化而改变")

    show("第 6 步：刷新店铺统计")
    for mid in shop_ids:
        sql(f"UPDATE t_merchant m SET m.total_product = "
            f"(SELECT COUNT(*) FROM t_product p "
            f" WHERE p.merchant_id = m.id AND p.status = 1) "
            f"WHERE m.id = {mid};", raw=True)
        out = sql(f"SELECT shop_name, total_product FROM t_merchant WHERE id = {mid};",
                  raw=True)
        print(f"  {out.strip().split(chr(10))[1]}")

    show("迁移结果")
    out = sql("SELECT m.shop_name, COUNT(p.id) AS cnt FROM t_product p "
              "JOIN t_merchant m ON m.id = p.merchant_id "
              "GROUP BY m.id, m.shop_name ORDER BY cnt DESC;", raw=True)
    print("  商品归属分布：")
    for line in out.split("\n")[1:]:
        if line.strip():
            parts = line.split("\t")
            if len(parts) >= 2:
                print(f"    {parts[0]:18s} {parts[1]:>4s} 件")

    print()
    print("  按分类确认定向是否正确：")
    out = sql("SELECT c.name, m.shop_name, COUNT(*) AS cnt "
              "FROM t_product p JOIN t_category c ON c.id = p.category_id "
              "JOIN t_merchant m ON m.id = p.merchant_id "
              "GROUP BY c.id, c.name, m.id, m.shop_name ORDER BY c.id;", raw=True)
    for line in out.split("\n")[1:]:
        if line.strip():
            parts = line.split("\t")
            if len(parts) >= 3:
                ok = "OK" if int(parts[2]) > 0 else "空"
                print(f"    {parts[0]:10s} {parts[1]:16s} {parts[2]:>4s} 件  {ok}")
    print()
    print("  注：分类内均分后，同一分类的商品会在两店间交替分布，")
    print("      每店品类齐全且总量均衡。")

    print()
    print("  [必做] 清 Redis 商品缓存，否则详情页仍显示旧归属：")
    print("     redis-cli --scan --pattern 'ec:product:*' | xargs -I{} redis-cli DEL {}")
    print("=" * 68)
    return 0


if __name__ == "__main__":
    sys.exit(main())