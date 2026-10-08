"""安全打包后端：停进程 → 打包 → 校验 jar 完整性。

解决一个反复出现的坑
--------------------
直接跑 ``mvn package``，若此时有 Java 进程在运行（jar 被 Windows 锁住），
spring-boot-maven-plugin 的 repackage 会失败::

    Failed to execute goal repackage: Unable to rename
    'ecommerce-api.jar' to 'ecommerce-api.jar.original'

**失败后果比报错本身严重得多**：插件已经删掉了原 jar，
只留下一个 58KB 的空壳（42 个条目，只有本项目自己的类）。

这个空壳 jar 的症状极具迷惑性：
- 能启动，日志里有 ``Started EcommerceApplication``
- 端口通、Tomcat 通、健康检查过
- 但**所有接口超时**，前端报"网络问题"、登录无反应

原因是请求进来后线程崩在日志实现上::

    Exception in thread "http-nio-8080-exec-1"
    java.lang.NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy

**曾因此排查了半小时以为是代码问题。** 所以打包后必须校验完整性，
不能只看命令退出码。

用法::

    python scripts/build_backend.py
"""

import os
import shutil
import subprocess
import sys
import zipfile

BACKEND = "E:/WorkBuddy/store/ecommerce/backend"
JAR = os.path.join(BACKEND, "ecommerce-api/target/ecommerce-api.jar")
JAR_ORIG = JAR + ".original"
MVN = "E:/devtools/mvnw.sh"
PY = "C:/Users/Hkzhen/.workbuddy/binaries/python/versions/3.13.12/python.exe"

MIN_SIZE_MB = 30      # 正常 fat jar 约 57MB
MIN_LIBS = 40        # 正常依赖 jar 数约 97
REQUIRED_LIBS = ["logback-classic", "spring-web-", "HikariCP",
                 "mysql-connector", "mybatis-plus"]


def step(msg):
    print(f"\n[{msg}]")


def stop_java():
    """停止运行中的 Java 进程（它们锁着 jar）。"""
    out = subprocess.run(["tasklist"], capture_output=True).stdout.decode(
        "utf-8", errors="replace")
    pids = []
    for line in out.split("\n"):
        if "java.exe" in line.lower():
            parts = line.split()
            if len(parts) >= 2 and parts[1].isdigit():
                pids.append(parts[1])
    if not pids:
        print("  无运行中的 Java 进程")
        return
    for pid in pids:
        # Git Bash 下 taskkill 的 /F 会被当成路径分隔符，需用 -f 或 MSYS_NO_PATHCONV
        subprocess.run(["taskkill", "/F", "/PID", pid],
                       capture_output=True,
                       env={**os.environ, "MSYS_NO_PATHCONV": "1"})
    import time
    time.sleep(3)
    print(f"  已停止 {len(pids)} 个 Java 进程")


def jar_size_mb(path):
    if not os.path.exists(path):
        return 0
    return os.path.getsize(path) / 1024 / 1024


def inspect_jar(path):
    """返回 (依赖jar数, 缺失的关键依赖列表)。"""
    try:
        with zipfile.ZipFile(path) as z:
            libs = [n for n in z.namelist() if n.startswith("BOOT-INF/lib/")]
        missing = [r for r in REQUIRED_LIBS
                   if not any(r in n for n in libs)]
        return len(libs), missing
    except Exception as e:
        print(f"  jar 无法读取：{e}")
        return 0, REQUIRED_LIBS


def remove_quiet(path):
    """删除文件。Windows 上偶发占用失败，重试几次。"""
    import time
    for i in range(3):
        try:
            if os.path.exists(path):
                os.remove(path)
            return True
        except OSError:
            time.sleep(1)
    return False


def main():
    print("=" * 60)
    print(" 安全打包（停进程 → 打包 → 校验完整性）")
    print("=" * 60)

    # ---- 1. 停进程 ----
    step("1/4 停止运行中的 Java 进程")
    stop_java()

    # ---- 2. 清理空壳 ----
    step("2/4 清理旧产物")
    sz = jar_size_mb(JAR)
    if sz and sz < MIN_SIZE_MB:
        print(f"  发现空壳 jar（{sz:.1f}MB < {MIN_SIZE_MB}MB），删除")
        remove_quiet(JAR)
        remove_quiet(JAR_ORIG)
    remove_quiet(JAR_ORIG)
    print("  完成")

    # ---- 3. 打包 ----
    step("3/4 执行打包（clean + package）")
    # mvnw.sh 是 shell 脚本，Windows 的 CreateProcess 不能直接执行
    # （OSError: [WinError 193] %1 不是有效的 Win32 应用程序），必须经 bash。
    r = subprocess.run(
        ["bash", MVN, "-q", "clean", "package", "-DskipTests"],
        cwd=BACKEND, capture_output=True)
    if r.returncode != 0:
        err = r.stderr.decode("utf-8", errors="replace")
        print("  [失败] mvn 返回非 0")
        for line in err.split("\n"):
            if "ERROR" in line:
                print("   ", line[:150])
        # 失败也会留下空壳，必须清掉
        remove_quiet(JAR)
        remove_quiet(JAR_ORIG)
        print("  已清理残留 jar")
        return 1
    print("  打包完成")

    # ---- 4. 校验 ----
    step("4/4 校验 jar 完整性")
    if not os.path.exists(JAR):
        print("  [失败] jar 不存在")
        return 1

    sz = jar_size_mb(JAR)
    nlibs, missing = inspect_jar(JAR)
    print(f"  大小: {sz:.1f} MB")
    print(f"  依赖 jar 数: {nlibs}")

    if sz < MIN_SIZE_MB or nlibs < MIN_LIBS or missing:
        print()
        print("  [失败] jar 不完整 —— 空壳 jar，启动后会报 NoClassDefFoundError")
        print(f"    大小 {sz:.1f}MB（应 >{MIN_SIZE_MB}）")
        print(f"    依赖 {nlibs} 个（应 >{MIN_LIBS}）")
        if missing:
            print(f"    缺失: {missing}")
        print("    典型症状：接口全部超时、前端报网络问题")
        remove_quiet(JAR)
        remove_quiet(JAR_ORIG)
        print("  已删除空壳，可重新打包")
        return 1

    print()
    print("=" * 60)
    print(" 打包成功，校验通过")
    print(f" 启动：cd \"{BACKEND}\"")
    print('   DB_PASSWORD=123456 "E:/devtools/jdk17/bin/java.exe" \\')
    print("     -jar ecommerce-api/target/ecommerce-api.jar")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())