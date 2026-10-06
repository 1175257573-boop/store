package com.ecommerce.benchmark;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀接口压测器（Java 17 虚拟线程）。
 *
 * <p><b>为什么不用 Python 压测</b>：CPython 的 GIL 让多线程无法真并行，
 * 压测客户端自己先成了瓶颈。之前用 Python 压到 2000 并发时，
 * 有 684 个请求在客户端就超时了，测出来的不是服务端能力而是客户端上限。</p>
 *
 * <p>Java 17 的虚拟线程以几 KB 栈内存承载上万并发，客户端开销可忽略，
 * 测出来的才是服务端真实能力。</p>
 *
 * <p>本工具只负责「打接口 + 记指标」，正确性判定交给 Python 脚本查库完成
 * （压测工具与业务断言分离，避免工具里塞业务逻辑）。</p>
 */
public class SeckillBenchmark {

    static {
        // Windows 默认把 System.out 编码成 GBK，中文在日志里会变成乱码，
        // 下游用 UTF-8 读会直接抛 UnicodeDecodeError。强制指定。
        System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 单次请求结果 */
    record Result(int code, long latencyUs, boolean ok, String error) { }

    public static void main(String[] args) throws Exception {
        Config cfg = Config.parse(args);

        line();
        out("秒杀接口压测器（Java 17 平台线程池）");
        line();
        out("目标接口  : " + cfg.url);
        out("活动/商品 : activity=" + cfg.activityId + " sku=" + cfg.skuId);
        out("并发数    : " + cfg.concurrency
                + " (线程数 " + Math.min(cfg.concurrency, Runtime.getRuntime().availableProcessors()) + ")");
        out("持续时间  : " + cfg.duration + " 秒");
        out("令牌数    : " + cfg.tokens.size());
        out("");

        if (cfg.warmup) {
            out("预热中（触发 JIT 编译 + 建连接）...");
            runPhase(cfg, cfg.concurrency, Math.min(cfg.concurrency / 2, 200), 3, true);
            out("预热完成");
        }

        Stats stats = runPhase(cfg, cfg.concurrency, cfg.tokens.size(), cfg.duration, false);

        out("");
        line();
        out("请求总数 : " + stats.total.get());
        out("成功     : " + stats.success.get() + "  (" + fmt(stats.successRate()) + "%)");
        out("失败     : " + stats.failed.get());
        out("耗时     : " + stats.elapsedMs + " ms");
        out("QPS      : " + Math.round(stats.qps()));
        line();
        out("延迟分布 (ms)");
        out("  最小   : " + fmt(stats.minMs()));
        out("  P50    : " + fmt(stats.p50Ms()));
        out("  P90    : " + fmt(stats.p90Ms()));
        out("  P99    : " + fmt(stats.p99Ms()));
        out("  P99.9  : " + fmt(stats.p999Ms()));
        out("  最大   : " + fmt(stats.maxMs()));
        out("  平均   : " + fmt(stats.avgMs()));
        line();
        out("业务返回码分布");
        stats.codeDist.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out("  code=" + pad(e.getKey(), 6)
                        + pad(e.getValue().get(), 10)
                        + fmt(e.getValue().get() * 100.0
                        / Math.max(stats.total.get(), 1)) + "%"));
        line();
        out("错误明细 (前 5)");
        stats.errorDist.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, AtomicLong>>comparingLong(
                        e -> e.getValue().get()).reversed())
                .limit(5)
                .forEach(e -> out("  " + e.getKey() + " : " + e.getValue().get()));
        line();
        out("判定: " + classify(stats));
        line();
    }

    /** 跑一轮压测 */
    private static Stats runPhase(Config cfg, int concurrency, int tokenCount,
                                 int seconds, boolean warmup) throws Exception {
        Stats stats = new Stats();
        long start = System.currentTimeMillis();
        long deadline = start + seconds * 1000L;

        // 线程池大小必须 >= concurrency，否则 ready.await() 会死锁：
        // 提交了 concurrency 个任务但只有 N 个线程能跑，剩下 N+ 个永远等不到 ready。
        // 因此这里「一个并发对应一个线程」，多进程并行时由操作系统调度抢占 CPU。
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger seq = new AtomicInteger();

        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                int myIdx = idx;
                int size = cfg.tokens.size();
                int span = Math.min(tokenCount, size);
                while (System.currentTimeMillis() < deadline) {
                    // 轮转令牌，模拟不同用户；同一用户连发会被防重全拦掉
                    String token = cfg.tokens.get(
                            Math.floorMod(myIdx + seq.getAndIncrement(), span));
                    stats.record(cfg.post(token));
                }
            });
        }

        // 等所有线程就位再同时发压，避免启动斜坡干扰测量
        ready.await();
        go.countDown();

        Thread monitor = new Thread(() -> {
            long lastReport = System.currentTimeMillis();
            long lastTotal = 0;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(1000);
                    long cur = stats.total.get();
                    long now = System.currentTimeMillis();
                    if (cur > lastTotal && !warmup) {
                        out("\r    进行中 " + cur + " 请求  "
                                + Math.round((cur - lastTotal) * 1000.0
                                / (now - lastReport)) + " QPS   ");
                        lastTotal = cur;
                        lastReport = now;
                    }
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        Thread.sleep(800);   // 等在途请求收尾
        executor.shutdown();
        executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);

        stats.elapsedMs = System.currentTimeMillis() - start;
        return stats;
    }

    /** 按结果给可读判定 */
    private static String classify(Stats s) {
        if (s.failed.get() == 0) {
            return "全部成功，服务端还有余量";
        }
        double errRate = s.failed.get() * 100.0 / Math.max(s.total.get(), 1);
        if (errRate < 1) {
            return "轻微失败 (错误率 < 1%)，已接近上限";
        }
        if (errRate < 5) {
            return "明显过载 (错误率 1%~5%)，已越过容量拐点";
        }
        return "严重过载 (错误率 > 5%)，服务端已崩溃";
    }

    // ==================== 输出小工具 ====================

    private static void line() {
        out("=".repeat(74));
    }

    private static void out(String s) {
        System.out.println(s);
    }

    /** 保留 2 位小数的字符串 */
    private static String fmt(double d) {
        return String.format("%.2f", d);
    }

    /** 左对齐补空格 */
    private static String pad(Object v, int width) {
        String s = String.valueOf(v);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ==================== 指标收集 ====================

    /**
     * 延迟用直方图而非全量样本。
     * <p>十万级请求每条存一个 long 就是 800KB，采样到百万级会占满堆；
     * 直方图固定 10 万个计数器，内存恒定。</p>
     */
    static class Stats {
        final AtomicLong total = new AtomicLong();
        final AtomicLong success = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        long elapsedMs;

        final ConcurrentHashMap<String, AtomicLong> codeDist = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicLong> errorDist = new ConcurrentHashMap<>();

        private static final int BUCKET_US = 100;      // 桶宽 0.1ms
        private static final int BUCKETS = 100000;      // 上限 10s
        final AtomicInteger[] hist = new AtomicInteger[BUCKETS];
        final AtomicLong sumUs = new AtomicLong();
        final AtomicLong minUs = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong maxUs = new AtomicLong();

        Stats() {
            for (int i = 0; i < BUCKETS; i++) {
                hist[i] = new AtomicInteger();
            }
        }

        void record(Result r) {
            total.incrementAndGet();
            codeDist.computeIfAbsent(String.valueOf(r.code()),
                    k -> new AtomicLong()).incrementAndGet();
            if (r.ok()) {
                success.incrementAndGet();
            } else {
                failed.incrementAndGet();
                errorDist.computeIfAbsent(
                        r.error() == null ? "unknown" : r.error(),
                        k -> new AtomicLong()).incrementAndGet();
            }
            long us = r.latencyUs();
            sumUs.addAndGet(us);
            minUs.accumulateAndGet(us, Math::min);
            maxUs.accumulateAndGet(us, Math::max);
            int idx = (int) Math.min(us / BUCKET_US, BUCKETS - 1);
            hist[idx].incrementAndGet();
        }

        double successRate() {
            return total.get() == 0 ? 0 : success.get() * 100.0 / total.get();
        }

        double qps() {
            return elapsedMs == 0 ? 0 : total.get() * 1000.0 / elapsedMs;
        }

        double minMs() {
            return minUs.get() / 1000.0;
        }

        double maxMs() {
            return maxUs.get() / 1000.0;
        }

        double avgMs() {
            long t = total.get();
            return t == 0 ? 0 : sumUs.get() / 1000.0 / t;
        }

        /** 百分位：累加直方图找到目标位置 */
        private double percentileMs(double p) {
            long target = (long) (total.get() * p / 100.0);
            if (target == 0) {
                return 0;
            }
            long acc = 0;
            for (int i = 0; i < BUCKETS; i++) {
                acc += hist[i].get();
                if (acc >= target) {
                    return (i * BUCKET_US + BUCKET_US / 2) / 1000.0;
                }
            }
            return maxMs();
        }

        double p50Ms() {
            return percentileMs(50);
        }

        double p90Ms() {
            return percentileMs(90);
        }

        double p99Ms() {
            return percentileMs(99);
        }

        double p999Ms() {
            return percentileMs(99.9);
        }
    }

    // ==================== 参数与请求 ====================

    static class Config {
        String url;
        long activityId = 1;
        long skuId = 1;
        int concurrency = 200;
        int duration = 30;
        boolean warmup = true;
        int requestTimeoutMs = 10000;
        List<String> tokens = new ArrayList<>();

        static Config parse(String[] args) throws IOException {
            Config c = new Config();
            String tokenFile = null;
            for (int i = 0; i < args.length - 1; i++) {
                switch (args[i]) {
                    case "--url" -> c.url = args[++i];
                    case "--activity" -> c.activityId = Long.parseLong(args[++i]);
                    case "--sku" -> c.skuId = Long.parseLong(args[++i]);
                    case "--concurrency" -> c.concurrency = Integer.parseInt(args[++i]);
                    case "--duration" -> c.duration = Integer.parseInt(args[++i]);
                    case "--tokens" -> tokenFile = args[++i];
                    case "--timeout" -> c.requestTimeoutMs = Integer.parseInt(args[++i]);
                    case "--no-warmup" -> c.warmup = false;
                    default -> { }
                }
            }
            if (tokenFile != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(tokenFile), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            c.tokens.add(line);
                        }
                    }
                }
            }
            if (c.tokens.isEmpty()) {
                throw new IllegalStateException(
                        "令牌为空：请先运行 python scripts/gen_benchmark_tokens.py 3000");
            }
            return c;
        }

        /** 发起一次秒杀请求 */
        Result post(String token) {
            long t0 = System.nanoTime();
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(requestTimeoutMs);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                // 必须开 keep-alive：关掉后每次请求都新建 TCP 连接，
                // 测到的就是握手开销而非服务能力
                conn.setRequestProperty("Connection", "keep-alive");

                String body = "{\"activityId\":" + activityId
                        + ",\"skuId\":" + skuId
                        + ",\"requestId\":\"" + activityId + "-" + skuId + "-"
                        + System.nanoTime() + "\""
                        + ",\"quantity\":1}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                String resp = readAll(status >= 400
                        ? conn.getErrorStream() : conn.getInputStream());
                long us = (System.nanoTime() - t0) / 1000;

                int bizCode = -99;
                int idx = resp.indexOf("\"code\":");
                if (idx >= 0) {
                    int end = resp.indexOf(',', idx);
                    if (end < 0) {
                        end = resp.length();
                    }
                    try {
                        bizCode = Integer.parseInt(
                                resp.substring(idx + 7, end).replaceAll("[^0-9]", ""));
                    } catch (NumberFormatException ignore) {
                        bizCode = -98;
                    }
                }
                return new Result(bizCode, us, status == 200,
                        status == 200 ? null : "HTTP " + status);
            } catch (Exception e) {
                long us = (System.nanoTime() - t0) / 1000;
                return new Result(-97, us, false, e.getClass().getSimpleName());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private static String readAll(InputStream is) {
            if (is == null) {
                return "";
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            } catch (IOException e) {
                return "";
            }
        }
    }
}
