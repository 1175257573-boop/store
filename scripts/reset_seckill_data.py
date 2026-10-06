#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
重置秒杀测试数据，让依赖固定活动 ID 的脚本可以重复运行。

背景：压测脚本会清空自己建的活动，但会消耗掉内置演示活动（ID=1/2/3）的库存，
导致依赖这些活动的其他测试失败。本脚本把演示活动恢复到初始状态。
"""
import subprocess
import sys

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
REDIS = r"E:\devtools\redis\Redis-7.2.5-Windows-x64-msys2\redis-cli.exe"


def sql(q):
    return subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
         "-e", f"USE ecommerce; {q}"],
        capture_output=True).stdout.decode("utf-8", errors="replace")


def rcli(*args):
    return subprocess.run([REDIS, "-p", "6379"] + list(args),
                          capture_output=True).stdout.decode().strip()


def main():
    print("重置演示秒杀活动...")

    # 1. 清空所有秒杀订单与预扣流水（含压测残留）
    sql("DELETE FROM t_seckill_order_item;")
    sql("DELETE FROM t_seckill_order;")
    sql("DELETE FROM t_seckill_pre_deduct;")

    # 2. 恢复库存（按 total_stock 复位，清掉 locked/sold）
    sql("UPDATE t_seckill_stock SET available = total_stock, locked = 0, sold = 0;")

    # 3. 活动状态恢复为进行中，且开始时间设为过去（保证立即生效）
    sql("UPDATE t_seckill_activity SET status = 1, "
        "start_time = DATE_SUB(NOW(), INTERVAL 1 DAY) "
        "WHERE end_time > NOW();")

    # 4. Redis 侧：清队列、消费标记、限流 key
    for pattern in ["sk:queue:*", "sk:consumed:*", "sk:rate:*", "sk:retry:*"]:
        for k in rcli("--scan", "--pattern", pattern).splitlines():
            if k:
                rcli("DEL", k)

    # 5. 重新预热 Redis 库存分桶（按 DB 的 total_stock 重新分桶）
    rows = sql("SELECT activity_id, sku_id, total_stock FROM t_seckill_stock;")
    activity_buckets = {}
    for line in rows.splitlines()[1:]:
        if not line.strip() or "Warning" in line:
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        activity_buckets.setdefault(parts[0], []).append(
            (int(parts[1]), int(parts[2])))

    total_keys = 0
    for aid, goods in activity_buckets.items():
        for sku, total in goods:
            buckets = 10
            base, rem = total // buckets, total % buckets
            for i in range(buckets):
                qty = base + (1 if i < rem else 0)
                rcli("SET", f"sk:stock:{aid}:{sku}:{i}", str(qty))
                total_keys += 1
            rcli("SET", f"sk:init:{aid}:{sku}", "1", "EX", "3600")

    print(f"  订单与流水已清空")
    print(f"  库存已复位")
    print(f"  Redis 重新预热 {total_keys} 个分桶 key")
    print("完成")


if __name__ == "__main__":
    main()
