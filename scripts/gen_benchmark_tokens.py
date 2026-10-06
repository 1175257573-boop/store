#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
为压测器生成 JWT 令牌。

为什么不用登录接口：压测需要几千个用户，每次登录 BCrypt 要 50ms，
光登录就得几分钟，而且登录本身也会污染限流计数。直接复用 demo 账号的
哈希造用户，再批量登录一次把 token 落盘，压测器启动时读文件即可。

用法：
  python gen_benchmark_tokens.py 3000
  → 生成 E:/WorkBuddy/Temp/bench_tokens.txt
"""
import io
import json
import os
import subprocess
import tempfile
import sys
import threading
import time
import urllib.request
from datetime import datetime, timedelta

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
OUT = r"E:\WorkBuddy\Temp\bench_tokens.txt"
PREFIX = "bench"


def sql(q):
    return subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
         "-e", f"USE ecommerce; {q}"],
        capture_output=True).stdout.decode("utf-8", errors="replace")


def ensure_users(n):
    """批量建压测用户，密码统一复用 demo 的 BCrypt 哈希"""
    out = sql(f"SELECT COUNT(*) FROM t_user WHERE username LIKE '{PREFIX}\\_%';")
    have = int([l for l in out.splitlines() if l.strip() and "Warning" not in l][-1])
    if have >= n:
        print(f"  已存在 {have} 个压测用户")
        return n

    pwd_out = sql("SELECT password FROM t_user WHERE username='demo' LIMIT 1;")
    pwd = [l for l in pwd_out.splitlines() if l.strip() and "Warning" not in l][-1]

    need = n - have
    batch = 500
    for start in range(0, need, batch):
        rows = ",".join(
            f"('{PREFIX}_{i:05d}','{pwd}','压测{i}',1,0,0)"
            for i in range(start, min(start + batch, need)))
        # Windows 命令行有 32K 限制，长 INSERT 会被 shell 拒绝
        # （FileNotFoundError: [WinError 206]）。写成文件再导入。
        sql_file = os.path.join(tempfile.gettempdir(), "bench_users.sql")
        with io.open(sql_file, "w", encoding="utf-8") as f:
            f.write("USE ecommerce;\n")
            f.write("INSERT IGNORE INTO t_user "
                    "(username,password,nickname,status,role,gender) VALUES ")
            f.write(rows + ";\n")
        subprocess.run(
            [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4"],
            stdin=io.open(sql_file, "rb"), capture_output=True)

    out = sql(f"SELECT COUNT(*) FROM t_user WHERE username LIKE '{PREFIX}\\_%';")
    total = int([l for l in out.splitlines() if l.strip() and "Warning" not in l][-1])
    print(f"  压测用户总数: {total}")
    return total


def login_batch(count, workers=30):
    """并发登录拿 token"""
    tokens = [None] * count
    lock = threading.Lock()
    idx = [0]
    done = [0]
    total = count

    def worker():
        while True:
            with lock:
                i = idx[0]
                if i >= total:
                    return
                idx[0] = i + 1
            u = f"{PREFIX}_{i:05d}"
            try:
                req = urllib.request.Request(
                    f"{BASE}/user/login",
                    data=json.dumps({"username": u, "password": "123456"}).encode(),
                    method="POST")
                req.add_header("Content-Type", "application/json")
                with urllib.request.urlopen(req, timeout=20) as x:
                    d = json.loads(x.read().decode())
                    if d.get("data"):
                        tokens[i] = d["data"]["token"]
            except Exception:
                pass
            with lock:
                done[0] += 1
                if done[0] % 500 == 0:
                    print(f"    已登录 {done[0]}/{total}")

    ths = [threading.Thread(target=worker) for _ in range(workers)]
    t0 = time.time()
    for t in ths:
        t.start()
    for t in ths:
        t.join()

    ok = [t for t in tokens if t]
    print(f"  登录完成: {len(ok)}/{total}，耗时 {time.time()-t0:.1f}s")
    return ok


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 3000
    print("=" * 60)
    print("生成压测令牌")
    print("=" * 60)

    print("\n[1] 准备压测用户")
    ensure_users(n)

    print(f"\n[2] 并发登录 {n} 个用户")
    tokens = login_batch(n)

    if not tokens:
        print("登录失败，检查后端是否启动")
        sys.exit(1)

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(tokens))
    print(f"\n已写入 {OUT}（{len(tokens)} 个令牌）")

    print("\n[3] 提示")
    print("  压测前可先跑 reset_seckill_data.py 复位演示活动")


if __name__ == "__main__":
    main()
