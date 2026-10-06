#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
多进程压测调度器
================================================================
为什么需要多进程：

Java 17 没有虚拟线程（`Executors.newVirtualThreadPerTaskExecutor()` 是
Java 21 才有的 API），平台线程数受 CPU 核数限制（20 核 → 20 线程）。
客户端线程数不够，压测客户端自己先成了瓶颈。

而且即使单进程开 20 线程，压测客户端和后端还在**同一台机器上抢 CPU**，
测出的 QPS 会被严重低估。

方案：**多进程 + 每进程一个线程池**，用操作系统调度把 CPU 用满。
每进程输出一行结果，最后由本脚本汇总 QPS。

用法：
  python run_cluster_benchmark.py --concurrency 400 --processes 8 --duration 30
"""
import io
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
REDIS = r"E:\devtools\redis\Redis-7.2.5-Windows-x64-msys2\redis-cli.exe"
JAVA = r"E:\devtools\jdk17\bin\java.exe"
CLASSES = r"E:\WorkBuddy\store\ecommerce\backend\ecommerce-api\target\classes"
TOKENS = r"E:\WorkBuddy\Temp\bench_tokens.txt"
RESULT_DIR = r"E:\WorkBuddy\Temp\bm-results"


def sql(q):
    return subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
         "-e", f"USE ecommerce; {q}"],
        capture_output=True).stdout.decode("utf-8", errors="replace")


def rcli(*args):
    return subprocess.run([REDIS, "-p", "6379"] + list(args),
                          capture_output=True).stdout.decode().strip()


def reset_env(concurrency_estimate):
    """每轮压测前重置：清订单流水、复位库存、重预热 Redis、清限流"""
    sql("DELETE FROM t_seckill_order_item;")
    sql("DELETE FROM t_seckill_order;")
    sql("DELETE FROM t_seckill_pre_deduct;")
    sql("UPDATE t_seckill_stock SET available=total_stock, locked=0, sold=0;")
    sql("UPDATE t_seckill_activity SET status=1, "
        "start_time=DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE end_time > NOW();")

    for pattern in ["sk:queue:*", "sk:consumed:*", "sk:rate:*", "sk:retry:*"]:
        for k in rcli("--scan", "--pattern", pattern).splitlines():
            if k:
                rcli("DEL", k)

    rows = sql("SELECT activity_id, sku_id, total_stock FROM t_seckill_stock;")
    buckets_by_act = {}
    for line in rows.splitlines()[1:]:
        if not line.strip() or "Warning" in line:
            continue
        p = line.split("\t")
        if len(p) >= 3:
            buckets_by_act.setdefault(p[0], []).append((int(p[1]), int(p[2])))

    keys = 0
    for aid, goods in buckets_by_act.items():
        for sku, total in goods:
            # 分桶数随并发提高：并发越高，单 key 热点越明显
            b = 50 if concurrency_estimate >= 500 else 20
            base, rem = total // b, total % b
            for i in range(b):
                rcli("SET", f"sk:stock:{aid}:{sku}:{i}", str(base + (1 if i < rem else 0)))
                keys += 1
            rcli("SET", f"sk:init:{aid}:{sku}", "1", "EX", "3600")
    return keys


def run_process(idx, concurrency, duration, activity, sku, token_file, tag):
    """跑一个压测进程"""
    out_file = os.path.join(RESULT_DIR, f"p{idx}_{tag}.txt")
    cmd = [
        JAVA, "-cp", CLASSES,
        "com.ecommerce.benchmark.SeckillBenchmark",
        "--url", f"{BASE}/seckill",
        "--activity", str(activity),
        "--sku", str(sku),
        "--concurrency", str(concurrency),
        "--duration", str(duration),
        "--tokens", token_file,
        "--no-warmup",          # 每个进程各自预热太慢，由调度器统一处理
    ]
    with open(out_file, "w", encoding="utf-8") as f:
        subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, timeout=duration + 90)
    return out_file


def parse_result(path):
    """从输出文件里提取指标"""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except Exception:
        return None
    r = {}
    # 标签与冒号之间的空格数不固定，用 \s+ 宽松匹配
    patterns = {
        "total": r"请求总数\s*:\s*(\d+)",
        "success": r"成功\s*:\s*(\d+)",
        "failed": r"失败\s*:\s*(\d+)",
        "elapsed": r"耗时\s*:\s*(\d+)\s*ms",
        "qps": r"QPS\s*:\s*(\d+)",
        "p50": r"P50\s*:\s*([\d.]+)",
        "p90": r"P90\s*:\s*([\d.]+)",
        "p99": r"P99\s*:\s*([\d.]+)",
        "p999": r"P99\.9\s*:\s*([\d.]+)",
        "max": r"最大\s*:\s*([\d.]+)",
    }
    for k, pat in patterns.items():
        m = re.search(pat, text)
        if m:
            r[k] = float(m.group(1)) if "." in m.group(1) else int(m.group(1))
    # 错误分类
    errs = dict(re.findall(r"^\s+(\w+(?:Exception|Error|Timeout)?) : (\d+)$",
                             text, re.M))
    r["errors"] = {k: int(v) for k, v in errs.items()}
    # 业务码分布
    codes = dict(re.findall(r"code=(-?\d+)\s+(\d+)", text))
    r["codes"] = {k: int(v) for k, v in codes.items()}
    return r if "total" in r else None


def check_correctness(activity, sku, expected_total):
    """压测后校验防超卖判据"""
    checks = []

    def q1(stmt):
        out = sql(stmt)
        lines = [l for l in out.splitlines() if l.strip() and "Warning" not in l]
        return int(lines[-1].split("\t")[0]) if len(lines) > 1 else 0

    order_cnt = q1("SELECT COUNT(*) FROM t_seckill_order "
                    f"WHERE activity_id={activity} AND status IN (0,1);")
    order_qty = q1("SELECT IFNULL(SUM(quantity),0) FROM t_seckill_order "
                   f"WHERE activity_id={activity} AND status IN (0,1);")
    dup = q1("SELECT COUNT(*) FROM (SELECT user_id,sku_id FROM t_seckill_order "
              f"WHERE activity_id={activity} GROUP BY user_id,sku_id "
              "HAVING COUNT(*)>1) x;")
    avail = q1(f"SELECT IFNULL(SUM(available),0) FROM t_seckill_stock "
               f"WHERE activity_id={activity};")
    locked = q1(f"SELECT IFNULL(SUM(locked),0) FROM t_seckill_stock "
                f"WHERE activity_id={activity};")
    sold = q1(f"SELECT IFNULL(SUM(sold),0) FROM t_seckill_stock "
              f"WHERE activity_id={activity};")
    total = q1(f"SELECT IFNULL(SUM(total_stock),0) FROM t_seckill_stock "
               f"WHERE activity_id={activity};")
    net_flow = q1(f"SELECT IFNULL(SUM(direction),0) FROM t_seckill_pre_deduct "
                   f"WHERE activity_id={activity} AND status!=2;")

    checks.append(("判据1 不超卖", order_qty <= total,
                   f"订单{order_qty}件 vs 库存{total}件"))
    checks.append(("判据2 库存非负", avail >= 0, f"available={avail}"))
    checks.append(("判据3 总量守恒", avail + locked + sold == total,
                   f"{avail}+{locked}+{sold} != {total}"))
    checks.append(("判据4 无重复订单", dup == 0, f"重复{dup}组"))
    checks.append(("判据5 预扣一致", net_flow == order_qty,
                   f"净预扣{net_flow} vs 订单{order_qty}"))
    return checks, dict(orders=order_cnt, order_qty=order_qty, avail=avail,
                        locked=locked, sold=sold, total=total, net_flow=net_flow)


def main():
    conc = int(sys.argv[1]) if len(sys.argv) > 1 else 400
    procs = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    dur = int(sys.argv[3]) if len(sys.argv) > 3 else 30
    activity = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    sku = int(sys.argv[5]) if len(sys.argv) > 5 else 1

    os.makedirs(RESULT_DIR, exist_ok=True)
    per = max(1, conc // procs)
    tag = f"c{conc}"

    print("=" * 74)
    print("多进程压测调度器")
    print("=" * 74)
    print(f"  总并发    : {conc}  ({procs} 进程 x {per})")
    print(f"  持续时间  : {dur} 秒")
    print(f"  活动/商品 : activity={activity} sku={sku}")
    print(f"  CPU 核数  : {os.cpu_count()}")

    keys = reset_env(conc)
    print(f"  环境已重置，Redis 预热 {keys} 个分桶 key")
    print()

    # 预热：先把 JIT 和连接池拉起来，不计入正式结果
    print("预热中...")
    reset_env(conc)
    t0 = time.time()
    run_process(0, min(per, 100), 4, activity, sku, TOKENS, "warmup")
    print(f"  预热完成 ({time.time()-t0:.0f}s)，重置环境后正式压测")
    keys = reset_env(conc)
    print()

    # 正式压测：所有进程同一时刻起跑
    print(f"开始压测（{dur} 秒）...")
    results = []
    threads = []
    t0 = time.time()

    def worker(i):
        p = run_process(i, per, dur, activity, sku, TOKENS, tag)
        results.append(p)

    for i in range(procs):
        t = threading.Thread(target=worker, args=(i,))
        threads.append(t)
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    wall = time.time() - t0
    print(f"压测结束，墙钟耗时 {wall:.1f}s\n")

    # 汇总各进程结果
    parsed = [parse_result(p) for p in results]
    parsed = [p for p in parsed if p]
    if not parsed:
        print("所有进程均无有效输出，输出文件前 20 行：")
        for p in results[:2]:
            with open(p, encoding="utf-8", errors="replace") as f:
                print("".join(f.readlines()[:20]))
        return

    total_req = sum(p.get("total", 0) for p in parsed)
    total_ok = sum(p.get("success", 0) for p in parsed)
    total_fail = sum(p.get("failed", 0) for p in parsed)
    # 墙钟比进程内统计更准：进程启动有先后，QPS 要用实际并发窗口算
    real_qps = total_req / wall if wall > 0 else 0

    all_codes = {}
    all_errs = {}
    for p in parsed:
        for k, v in p.get("codes", {}).items():
            all_codes[k] = all_codes.get(k, 0) + v
        for k, v in p.get("errors", {}).items():
            all_errs[k] = all_errs.get(k, 0) + v

    # 百分位：多进程无法精确合并，取各进程最大值做保守估计
    p99 = max(p.get("p99", 0) for p in parsed)
    p999 = max(p.get("p999", 0) for p in parsed)
    p50 = max(p.get("p50", 0) for p in parsed)
    worst = max(p.get("max", 0) for p in parsed)

    print("=" * 74)
    print(f"并发 {conc} 结果")
    print("=" * 74)
    print(f"  总请求    : {total_req}")
    print(f"  成功      : {total_ok} "
          f"({total_ok*100.0/max(total_req,1):.2f}%)")
    print(f"  失败      : {total_fail}")
    print(f"  墙钟耗时  : {wall:.1f} s")
    print(f"  真实 QPS  : {real_qps:.0f}")
    print("-" * 74)
    print(f"  P50       : {p50:.1f} ms")
    print(f"  P99       : {p99:.1f} ms")
    print(f"  P99.9     : {p999:.1f} ms")
    print(f"  最慢      : {worst:.1f} ms")
    print("-" * 74)
    print("  业务返回码")
    for k, v in sorted(all_codes.items(), key=lambda x: -x[1]):
        print(f"    code={k:<6} {v:>10}  ({v*100.0/max(total_req,1):.1f}%)")
    if all_errs:
        print("  错误类型")
        for k, v in sorted(all_errs.items(), key=lambda x: -x[1])[:5]:
            print(f"    {k:<24} {v}")
    print("-" * 74)

    # 消费落单（异步链路）
    print("  消费异步落单...")
    consume_rounds = 0
    for _ in range(60):
        try:
            req = urllib.request.Request(f"{BASE}/seckill/admin/consume-cluster"
                                         f"?batchSize=500&consumers=16",
                                         data=b"", method="POST")
            tok = get_admin_token()
            req.add_header("Authorization", "Bearer " + tok)
            req.add_header("Content-Type", "application/json")
            with urllib.request.urlopen(req, timeout=30) as x:
                n = json.loads(x.read().decode()).get("data") or 0
            consume_rounds += 1
            if n == 0:
                break
        except Exception:
            break
    print(f"  消费完成（{consume_rounds} 轮）")
    print()

    checks, detail = check_correctness(activity, sku, total_req)
    print("=" * 74)
    print("防超卖判据")
    print("=" * 74)
    print(f"  {detail}")
    print()
    all_pass = True
    for name, ok, msg in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}"
              f"{'' if ok else '  -> ' + msg}")
        all_pass = all_pass and ok
    print("=" * 74)

    # 追加本轮数据到汇总文件，便于事后画曲线
    with io.open(RESULT_DIR + "/summary.csv", "a", encoding="utf-8") as sf:
        sf.write(f"{conc},{total_req},{total_ok},{total_fail},"
                 f"{real_qps:.0f},{p50:.1f},{p99:.1f}\n")
    print(f"  已追加到 {RESULT_DIR}/summary.csv")

    verdict = ("全部成功，服务端有余量" if total_fail == 0
               else f"错误率 {total_fail*100.0/max(total_req,1):.2f}%，"
                    f"{'接近上限' if total_fail*100.0/max(total_req,1) < 1 else '已过载'}")
    print(f"容量判定: {verdict}")
    print(f"正确性  : {'5/5 全部通过' if all_pass else '存在失败判据'}")
    print("=" * 74)
    return real_qps, total_fail, all_pass


def get_admin_token():
    req = urllib.request.Request(f"{BASE}/user/login",
                                 data=json.dumps({"username": "admin",
                                                  "password": "123456"}).encode(),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=15) as x:
        return json.loads(x.read().decode())["data"]["token"]


if __name__ == "__main__":
    main()
