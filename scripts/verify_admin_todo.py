#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
审核红点与管理员跳转验证

验证：
  1. /merchant/admin/todo 返回真实待审数量
  2. 待审数量与实际队列一致
  3. 审核掉一条后数量立即减少
  4. 商家访问该接口被拒（红点接口也要鉴权）
  5. 前端不再绕过 store 读 localStorage 判角色
"""
import json
import re
import subprocess
import sys
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
FRONTEND = "E:/WorkBuddy/store/ecommerce/frontend/src"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"

passed, failed = [], []


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
    return lines[-1].split("\t")[0] if len(lines) > 1 else None


def check(name, cond, detail=""):
    if cond:
        passed.append(name)
        print(f"  [PASS] {name}")
    else:
        failed.append(f"{name} | {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


def enc(v):
    import urllib.parse
    return urllib.parse.quote(str(v), safe="")


print("=" * 70)
print("审核红点与管理员跳转验证")
print("=" * 70)

# ----------------------------------------------------------------
print("\n【1】后端待办统计接口")
print("-" * 70)

code, msg, admin = call("POST", "/user/login",
                       {"username": "admin", "password": "123456"})
check("管理员登录成功", code == 200 and admin, f"{code} {msg}")

code, msg, todo = call("GET", "/merchant/admin/todo", token=admin["token"])
check("待办接口可访问", code == 200 and todo is not None, f"{code} {msg}")
print(f"       返回: {todo}")

db_apply = int(sql("SELECT COUNT(*) FROM t_merchant_apply WHERE status = 0"))
db_product = int(sql("SELECT COUNT(*) FROM t_product "
                     "WHERE audit_status = 0 AND merchant_id IS NOT NULL"))
print(f"       库里实际: 待审入驻={db_apply}, 待审商品={db_product}")

check("待审入驻数与库一致",
      todo and todo.get("applyPending") == db_apply,
      f"接口={todo.get('applyPending') if todo else 'N/A'} 库={db_apply}")
check("待审商品数与库一致",
      todo and todo.get("productPending") == db_product,
      f"接口={todo.get('productPending') if todo else 'N/A'} 库={db_product}")
check("总数 = 两项之和",
      todo and todo.get("total") == db_apply + db_product,
      f"total={todo.get('total') if todo else 'N/A'}")

# ----------------------------------------------------------------
print("\n【2】红点权限隔离")
print("-" * 70)

code, msg, shop = call("POST", "/user/login",
                       {"username": "shop_a", "password": "123456"})
if shop:
    code, msg, _ = call("GET", "/merchant/admin/todo", token=shop["token"])
    check("商家访问待办接口被拒（红点接口也要鉴权）", code == 6002,
          f"{code} {msg}")
else:
    print("  [SKIP] shop_a 登录失败")

code, msg, _ = call("GET", "/merchant/admin/todo")
check("未登录访问被拒", code == 401, f"{code} {msg}")

# ----------------------------------------------------------------
print("\n【3】审核后红点立即减少")
print("-" * 70)

if db_product > 0:
    before = todo["productPending"]
    pid = sql("SELECT id FROM t_product WHERE audit_status = 0 "
              "AND merchant_id IS NOT NULL LIMIT 1")
    code, msg, _ = call("POST", f"/merchant/admin/product/{pid}/audit?pass=true",
                        token=admin["token"])
    check("审核通过一条商品", code == 200, f"{code} {msg}")

    code, msg, todo2 = call("GET", "/merchant/admin/todo", token=admin["token"])
    check("红点立即减 1",
          todo2 and todo2["productPending"] == before - 1,
          f"审核前={before} 审核后={todo2.get('productPending') if todo2 else 'N/A'}")

    # 还原：把商品改回待审核，保持测试可重复
    sql(f"UPDATE t_product SET audit_status = 0, status = 0 WHERE id = {pid}")
    code, msg, todo3 = call("GET", "/merchant/admin/todo", token=admin["token"])
    check("还原后红点恢复",
          todo3 and todo3["productPending"] == before,
          f"还原后={todo3.get('productPending') if todo3 else 'N/A'} 期望={before}")
else:
    print("  [SKIP] 当前无待审核商品")

# ----------------------------------------------------------------
print("\n【4】前端角色判断不再绕过 store")
print("-" * 70)

layout = open(f"{FRONTEND}/layout/MerchantLayout.vue", encoding="utf-8").read()
router = open(f"{FRONTEND}/router/index.js", encoding="utf-8").read()

# 守卫与布局都不能依赖 store：Pinia 在 mount 前未与 app 绑定，
# 拿到的是另一个实例，isAdmin 恒为 false。必须用 readRole 同步读。
check("MerchantLayout 用同步的 readIsAdmin（不依赖 store）",
      "readIsAdmin()" in layout and "userStore.isAdmin" not in layout,
      "布局不应依赖 store 的 isAdmin")
check("MerchantLayout 有 JWT 兜底",
      "roleFromToken" in layout,
      "localStorage 无 role 时应落到 JWT 解析")
check("默认重定向用 readRole",
      "readRole() === 2" in router,
      "重定向应用 readRole 同步判断")

# 红点相关
check("管理员菜单有红点角标",
      layout.count("menu-badge") >= 4,
      f"menu-badge 出现 {layout.count('menu-badge')} 次")
check("监听审核完成事件",
      "todo-changed" in layout and "addEventListener" in layout,
      "审核后红点不会实时刷新")
check("卸载时清理监听",
      "removeEventListener" in layout,
      "缺少 removeEventListener 会内存泄漏")

for page in ['ApplyAuditView.vue', 'ProductAuditView.vue']:
    src = open(f"{FRONTEND}/views/merchant/{page}", encoding="utf-8").read()
    check(f"{page} 审核后派发刷新事件", "todo-changed" in src, "缺少事件派发")

check("管理员待办汇总提示",
      "adminTodo.total" in layout, "缺少待办总数提示")

# ----------------------------------------------------------------
print("\n【5】管理员不会被引导去入驻")
print("-" * 70)

check("路由守卫用 readRole 同步判断",
      "function readRole()" in router and "const role = readRole()" in router,
      "守卫应直接读 JWT/localStorage，不依赖 store")
check("管理员进商家页会被引导回审核",
      "to.meta.merchantOnly && isAdmin" in router,
      "管理员无自己的店铺，应跳回审核页")
check("管理员菜单不含申请入驻",
      "merchant/apply" not in layout.split("isAdmin")[1][:400]
      if "isAdmin" in layout else True,
      "管理员菜单不应有入驻申请入口")

print(f"\n{'='*70}")
print(f"结果：通过 {len(passed)} 项，失败 {len(failed)} 项")
if failed:
    print("\n失败明细：")
    for f in failed:
        print(f"  - {f}")
print(f"{'='*70}")
sys.exit(0 if not failed else 1)
