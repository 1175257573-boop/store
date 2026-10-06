#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
入口路径与角色的对应关系验证

**为什么需要这个脚本**：
「顶栏入口 / 下拉菜单 / 路由重定向 / 菜单项」四处都写了跳转逻辑，
其中任何一处硬编码错路径，管理员点进去就会撞守卫被弹回。
之前的两次修复都是漏改了其中某一处。

本脚本把所有入口都列出来，逐条断言「目标路径与角色匹配」。
"""
import re
import sys

FRONTEND = "E:/WorkBuddy/store/ecommerce/frontend/src"

passed, failed = [], []


def check(name, cond, detail=""):
    if cond:
        passed.append(name)
        print(f"  [PASS] {name}")
    else:
        failed.append(f"{name} | {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


def read(p):
    return open(p, encoding="utf-8").read()


main = read(f"{FRONTEND}/layout/MainLayout.vue")
router = read(f"{FRONTEND}/router/index.js")
merchant_layout = read(f"{FRONTEND}/layout/MerchantLayout.vue")

print("=" * 70)
print("入口路径与角色的对应关系验证")
print("=" * 70)

# ----------------------------------------------------------------
print("\n【1】路由表：哪些页面属于商家、哪些属于管理员")
print("-" * 70)

# children: 在文件里出现两次（用户端 + 商家端），
# 必须定位到商家端那个：用 merchantRoutes 变量之后的那段
_mr = router.index("const merchantRoutes")
_seg = router[_mr:]
children_block = _seg.split("children: [", 1)[1].split("\n    ]", 1)[0]
routes = {}
for m in re.finditer(
        r"path:\s*'(\w[\w-]*)',\s*name:\s*'([\w-]+)',\s*\n\s*"
        r"component:\s*\(\)\s*=>\s*import\('@/views/merchant/(\w+)\.vue'\),\s*\n\s*"
        r"meta:\s*\{([^}]+)\}",
        children_block):
    path, name, comp, meta = m.groups()
    routes[path] = {
        "name": name,
        "component": comp,
        "merchantOnly": "merchantOnly: true" in meta,
        "requiresAdmin": "requiresAdmin: true" in meta,
    }

print("  路由清单:")
for path, info in routes.items():
    flags = []
    if info["merchantOnly"]:
        flags.append("仅商家")
    if info["requiresAdmin"]:
        flags.append("仅管理员")
    print(f"    /merchant/{path:<14} {info['component']:<16} {' '.join(flags) or '公共'}")

# 不写死页面数：每次新增功能都会变化，写死会变成噪音断言。
# 真正要保证的是「解析得出来」且「每项都有 meta 标记」。
check("路由表解析出全部页面", len(routes) >= 7, f"实际 {len(routes)} 个")

# ----------------------------------------------------------------
print("\n【2】顶栏入口")
print("-" * 70)

# 顶栏两个 router-link，管理员与商家各一条
top_links = re.findall(
    r'<router-link\s+v-(?:if="([^"]+)"|else-if="([^"]+)")\s*\n\s*'
    r'to="([^"]+)"[^>]*class="nav-item merchant-entry"[^>]*>\s*\n\s*'
    r'<el-icon><Shop\s*/></el-icon>([^<\n]+)',
    main)
print("  顶栏商家端入口:")
admin_link = None
merchant_link = None
for cond_a, cond_b, to, label in top_links:
    print(f"    标签={label.strip():<10} to={to}")
    if 'isAdmin' in (cond_a or cond_b):
        admin_link = to
    else:
        merchant_link = to

check("顶栏有两个分角色的入口", len(top_links) == 2, f"实际 {len(top_links)} 个")
check("管理员入口指向审核页", admin_link == "/merchant/audit",
      f"实际 {admin_link}")
check("商家入口指向概览页", merchant_link == "/merchant/dashboard",
      f"实际 {merchant_link}")

# 关键：管理员入口不能指向 merchantOnly 页面
if admin_link:
    target = admin_link.replace("/merchant/", "").replace("/merchant", "")
    info = routes.get(target)
    check("管理员入口不指向 merchantOnly 页面",
          info is not None and not info["merchantOnly"],
          f"{admin_link} 的 merchantOnly={info['merchantOnly'] if info else '路由不存在'}")

if merchant_link:
    target = merchant_link.replace("/merchant/", "").replace("/merchant", "")
    info = routes.get(target)
    check("商家入口指向 merchantOnly 页面",
          info is not None and info["merchantOnly"],
          f"{merchant_link} 的 merchantOnly={info['merchantOnly'] if info else '路由不存在'}")

# ----------------------------------------------------------------
print("\n【3】头像下拉菜单")
print("-" * 70)

cmds = re.findall(r'<el-dropdown-item\s+v-(?:if="([^"]+)"|else-if="([^"]+)")\s*\n?\s*'
                  r'command="(\w+)"', main)
print("  下拉菜单命令:")
for cond_a, cond_b, cmd in cmds:
    who = 'admin' if 'isAdmin' in (cond_a or cond_b) else \
          ('merchant' if 'isMerchant' in (cond_a or cond_b) else 'apply')
    print(f"    command={cmd:<10} 条件={who}")

check("管理员与商家用不同 command",
      'command="admin"' in main and 'command="merchant"' in main,
      "共用一个 command 会让处理函数必须再判角色，容易漏")

# 处理函数里每个 command 都有对应路由
handlers = re.findall(r"cmd === '(\w+)'\) \{\s*\n\s*router\.push\('([^']+)'\)", main)
handler_map = dict(handlers)
print("  处理函数映射:")
for c, p in handlers:
    print(f"    {c:<10} -> {p}")

for cmd, path in handlers:
    check(f"command '{cmd}' 有明确目标路径", path.startswith("/"), f"{cmd} 无路径")

admin_cmd_path = handler_map.get("admin")
merchant_cmd_path = handler_map.get("merchant")
check("admin 命令指向审核页", admin_cmd_path == "/merchant/audit", f"实际 {admin_cmd_path}")
check("merchant 命令指向概览页",
      merchant_cmd_path == "/merchant/dashboard", f"实际 {merchant_cmd_path}")

# ----------------------------------------------------------------
print("\n【4】路由默认重定向")
print("-" * 70)

redirect_ok = "readRole() === 2 ? '/merchant/audit' : '/merchant/dashboard'" in router
check("/merchant 默认重定向按角色分流", redirect_ok, "未找到按角色分流的函数式重定向")
# 守卫与重定向都用 readRole 同步读，不依赖 Pinia 时序
check("重定向用 readRole 同步判断（不受 Pinia 时序影响）",
      redirect_ok and "function readRole()" in router,
      "重定向应直接读 JWT/localStorage")

# ----------------------------------------------------------------
print("\n【5】侧边栏菜单项")
print("-" * 70)

menu_items = re.findall(
    r'<el-menu-item\s+v-(?:if="([^"]+)"|else-if="([^"]+)")[^>]*index="(/merchant/[\w-]*)"'
    r'[^>]*>\s*<el-icon><(\w+)\s*/></el-icon>([^<\n]+)',
    merchant_layout)
print("  侧边栏带条件的菜单项:")
for cond_a, cond_b, idx, icon, label in menu_items:
    who = 'admin' if 'isAdmin' in (cond_a or cond_b) else 'merchant'
    print(f"    [{who:<8}] {label.strip():<10} {idx}")

# 秒杀活动是「双角色共用」页面：商家发自己的，管理员发平台的，
# 由组件内部按角色加载数据。这类路由刻意不带角色限制。
SHARED_ROUTES = {"seckill-activity"}

for cond_a, cond_b, idx, icon, label in menu_items:
    who = 'admin' if 'isAdmin' in (cond_a or cond_b) else 'merchant'
    target = idx.replace("/merchant/", "")
    info = routes.get(target)
    name = label.strip()
    if info is None:
        check(f"侧边栏「{name}」有对应路由", False, f"{idx} 不在路由表")
        continue
    if target in SHARED_ROUTES:
        check(f"「{name}」是双角色共用页（不带角色限制）",
              not info["requiresAdmin"] and not info["merchantOnly"],
              f"{idx} 不应限制角色，数据范围由后端按 merchant_id 过滤")
    elif who == 'admin':
        check(f"管理员菜单「{name}」指向 requiresAdmin 页",
              info["requiresAdmin"],
              f"{idx} requiresAdmin={info['requiresAdmin']}")
    else:
        check(f"商家菜单「{name}」指向 merchantOnly 页",
              info["merchantOnly"],
              f"{idx} merchantOnly={info['merchantOnly']}")

# ----------------------------------------------------------------
print("\n【6】全局搜硬编码残留")
print("-" * 70)

# 任何地方都不该出现 v-if 同时含两种角色却只有一个硬编码路径
bad_pattern = re.findall(
    r'v-if="[^"]*isMerchant[^"]*\|\|[^"]*isAdmin[^"]*"[^>]*\n?\s*to="([^"]+)"', main)
check("不存在「两种角色共用一条硬编码路径」的入口", not bad_pattern,
      f"发现 {bad_pattern}")

print(f"\n{'='*70}")
print(f"结果：通过 {len(passed)} 项，失败 {len(failed)} 项")
if failed:
    print("\n失败明细：")
    for f in failed:
        print(f"  - {f}")
print(f"{'='*70}")
sys.exit(0 if not failed else 1)
