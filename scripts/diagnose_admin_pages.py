#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
管理员后台页面逐一体检

「点击后页面为空」可能有三类原因，这里逐个区分：
  A. 接口层失败  —— 页面拿到的是错误，渲染不出内容
  B. 数据层为空  —— 接口成功但返回空列表，看不出是坏了还是本来就没数据
  C. 渲染层崩溃  —— 组件 JS 报错，页面只剩框架
本脚本先把 A、B 测出来（可自动化），C 需要浏览器控制台才能定位。
"""
import json
import subprocess
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
FRONTEND = "E:/WorkBuddy/store/ecommerce/frontend/src"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"

results = []


def call(m, p, d=None, token=None, timeout=15):
    req = urllib.request.Request(
        BASE + p,
        data=json.dumps(d).encode() if d is not None else None,
        method=m)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as x:
            o = json.loads(x.read().decode())
            return o.get("code"), o.get("message"), o.get("data")
    except urllib.error.HTTPError as e:
        return -1, f"HTTP {e.code}", None
    except Exception as e:
        return -2, str(e), None


def sql(q):
    p = subprocess.run([MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
                        "-e", f"USE ecommerce; {q}"], capture_output=True)
    out = p.stdout.decode("utf-8", errors="replace")
    lines = [l for l in out.splitlines() if l.strip() and "Warning" not in l]
    return lines[-1] if len(lines) > 1 else None


print("=" * 72)
print("管理员后台页面体检")
print("=" * 72)

# ----------------------------------------------------------------
print("\n【1】登录并确认身份")
print("-" * 72)
code, msg, admin = call("POST", "/user/login",
                       {"username": "admin", "password": "123456"})
print(f"  admin 登录: code={code} role={admin.get('role') if admin else 'N/A'}")
if not admin:
    print("  登录失败，后续测试无法进行")
    sys.exit(1)
tok = admin["token"]

# ----------------------------------------------------------------
print("\n【2】逐个测试管理员页面调用的接口")
print("-" * 72)

PAGES = [
    ("入驻审核", "/merchant/admin/applies?status=0", "list"),
    ("商品审核", "/merchant/admin/audits", "list"),
    ("店铺列表", "/merchant/admin/shops", "list"),
    ("待办红点", "/merchant/admin/todo", "dict"),
    ("申请详情（无 id 时跳过）", None, None),
]

for name, path, kind in PAGES:
    if path is None:
        continue
    c, m, d = call("GET", path, token=tok)
    if c == 200:
        if kind == "list":
            n = len(d or [])
            print(f"  [OK]   {name:<18} 返回 {n} 条")
        else:
            print(f"  [OK]   {name:<18} {d}")
        results.append(("OK", name, f"{c}/{m}"))
    else:
        print(f"  [FAIL] {name:<18} code={c} msg={m}")
        results.append(("FAIL", name, f"{c}: {m}"))

# ----------------------------------------------------------------
print("\n【3】对比数据库真实数据")
print("-" * 72)

db_pending_apply = sql("SELECT COUNT(*) FROM t_merchant_apply WHERE status = 0")
db_pending_product = sql("SELECT COUNT(*) FROM t_product "
                         "WHERE audit_status = 0 AND merchant_id IS NOT NULL")
db_shops = sql("SELECT COUNT(*) FROM t_merchant WHERE status = 1")
print(f"  库里待审入驻: {db_pending_apply}")
print(f"  库里待审商品: {db_pending_product}")
print(f"  库里正常店铺: {db_shops}")

c, m, applies = call("GET", "/merchant/admin/applies?status=0", token=tok)
if applies is not None:
    same = str(len(applies)) == str(db_pending_apply).strip()
    print(f"  入驻申请 接口={len(applies)} 库={db_pending_apply} -> {'一致' if same else '不一致'}")

c, m, audits = call("GET", "/merchant/admin/audits", token=tok)
if audits is not None:
    same = str(len(audits)) == str(db_pending_product).strip()
    print(f"  商品审核 接口={len(audits)} 库={db_pending_product} -> {'一致' if same else '不一致'}")

# ----------------------------------------------------------------
print("\n【4】检查页面组件是否可能崩溃")
print("-" * 72)

import os
import re


def read(p):
    return open(p, encoding="utf-8").read()


layout = read(f"{FRONTEND}/layout/MerchantLayout.vue")
checks = [
    ("管理员判断用 store", "userStore.isAdmin" in layout),
    ("管理员跳过 loadShop", "isAdmin.value" in layout.split("async function loadShop")[1][:200]),
    ("管理员有红点数据源", "getAdminTodo" in layout),
    ("adminTodo 有默认值", "adminTodo = ref({ applyPending: 0" in layout
     or "adminTodo = ref({" in layout),
]
for name, ok in checks:
    print(f"  [{'OK' if ok else 'WARN'}] {name}")

# 关键：adminTodo 初值必须是带 0 的对象，
# 否则模板里 adminTodo.applyPending 在加载前会因 undefined 报错
m = re.search(r"adminTodo\s*=\s*ref\(([^)]*)\)", layout)
print(f"\n  adminTodo 初值: {m.group(1) if m else '未找到（可能报错）'}")

# 检查两个审核页面的 onMounted 是否可能抛错
for page in ["ApplyAuditView.vue", "ProductAuditView.vue"]:
    src = read(f"{FRONTEND}/views/merchant/{page}")
    has_onmounted = "onMounted(" in src
    has_try = src.count("try {") >= 2
    print(f"  {page}: onMounted={has_onmounted}, try块={src.count('try {')}")

# ----------------------------------------------------------------
print("\n【5】诊断结论")
print("-" * 72)

fails = [r for r in results if r[0] == "FAIL"]
if fails:
    print("  接口层有问题，页面空是「接口失败」导致的：")
    for _, name, detail in fails:
        print(f"    - {name}: {detail}")
else:
    print("  所有接口均返回 200，接口层没问题。")
    print()
    print("  「页面为空」最可能的三种原因，请按顺序排查：")
    print()
    print("  ① 浏览器控制台报错（最可能）")
    print("     按 F12 打开 Console，看有没有红色报错。")
    print("     常见：Uncaught TypeError / Cannot read properties of undefined")
    print("     如果报 adminTodo.xxx，说明红点初值有问题")
    print()
    print("  ② 登录态问题")
    print("     之前的 localStorage 缓存缺 role 字段，")
    print("     按 Ctrl+Shift+R 强刷，或退出重新登录")
    print()
    print("  ③ 数据本来就是空的")
    print(f"     库里待审入驻={db_pending_apply}、待审商品={db_pending_product}。")
    print("     如果都是 0，页面显示空列表是正常的（不是 bug）。")
    print("     可用其他账号提交入驻申请 / 发布商品来造数据。")

print(f"\n{'='*72}")
print("体检完成")
print(f"{'='*72}")
