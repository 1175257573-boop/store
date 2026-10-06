#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批量插入压测用户（直连 MySQL）。

走注册接口要逐个 BCrypt 加密，300 个用户约 30 秒；
直连写库复用固定哈希，秒级完成。压测用户密码统一 123456。

实现方式：生成 SQL 文件后用 mysql 客户端导入。
不用 subprocess 的 stdin 传 SQL —— Windows 下编码易出错。
"""
import subprocess
import sys
import tempfile
import os

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
BCRYPT = "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi"
TMP_SQL = os.path.join(tempfile.gettempdir(), "seed_stress_users.sql")


def main(count=300):
    # 分批 INSERT，每批 50 条，避免单条 SQL 过长
    batch = 50
    total_sql = ["USE ecommerce;", "SET NAMES utf8mb4;"]
    for start in range(0, count, batch):
        values = ",".join(
            f"('stress_{i:04d}','{BCRYPT}','压测用户{i}','139{i:08d}',1)"
            for i in range(start, min(start + batch, count))
        )
        total_sql.append(
            "INSERT IGNORE INTO t_user (username, password, nickname, phone, status) "
            f"VALUES {values};"
        )
    total_sql.append("SELECT COUNT(*) AS stress_users FROM t_user WHERE username LIKE 'stress_%';")

    with open(TMP_SQL, "w", encoding="utf-8") as f:
        f.write("\n".join(total_sql))

    p = subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4"],
        stdin=open(TMP_SQL, "rb"),
        capture_output=True
    )
    out = p.stdout.decode("utf-8", errors="replace")
    err = p.stderr.decode("utf-8", errors="replace")
    for line in out.splitlines():
        if line.strip():
            print(" ", line)
    real_err = [x for x in err.splitlines() if "Warning" not in x]
    if real_err:
        print("stderr:", "\n".join(real_err))
    os.remove(TMP_SQL)


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 300)
