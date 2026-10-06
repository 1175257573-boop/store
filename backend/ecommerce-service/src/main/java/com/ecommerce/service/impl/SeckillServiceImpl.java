package com.ecommerce.service.impl;

import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.constant.SeckillRedisKey;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.util.BizUtil;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.entity.SeckillActivity;
import com.ecommerce.dao.entity.SeckillOrder;
import com.ecommerce.dao.entity.SeckillOrderItem;
import com.ecommerce.dao.entity.SeckillPreDeduct;
import com.ecommerce.dao.entity.SeckillStock;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.dao.mapper.SeckillActivityMapper;
import com.ecommerce.dao.mapper.SeckillOrderItemMapper;
import com.ecommerce.dao.mapper.SeckillOrderMapper;
import com.ecommerce.dao.mapper.SeckillPreDeductMapper;
import com.ecommerce.dao.mapper.SeckillStockMapper;
import com.ecommerce.service.SeckillService;
import com.ecommerce.service.dto.SeckillRequestDTO;
import com.ecommerce.service.util.SeckillRateLimiter;
import com.ecommerce.service.util.SeckillRedisUtil;
import com.ecommerce.service.vo.SeckillResultVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 秒杀服务实现。
 *
 * <p><b>核心思路：Redis 是过滤器，DB 是账本。</b></p>
 *
 * <p>同步链路（用户请求内）：Redis 防重 → Redis Lua 原子预扣 → 写队列 → 返回。
 * 这一步全程不碰数据库，因此不受 DB 连接数限制，可以扛住十万级 QPS。</p>
 *
 * <p>异步链路（消费者内）：DB 条件更新扣库存（最终正确性依据）→ 写订单。
 * 这一步慢一点没关系，因为已经不在用户的关键路径上了。</p>
 *
 * <p><b>为什么必须两层都要</b>：只有 Redis 层，Redis 主从切换丢数据就会超卖；
 * 只有 DB 层，十万 QPS 会把连接池打满。两层各司其职才既快又准。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillStockMapper stockMapper;
    private final SeckillOrderMapper orderMapper;
    private final SeckillOrderItemMapper orderItemMapper;
    private final SeckillPreDeductMapper preDeductMapper;
    private final ProductMapper productMapper;
    private final SeckillRedisUtil redisUtil;
    private final SeckillRateLimiter rateLimiter;
    private final SeckillOrderCreator orderCreator;
    private final StringRedisTemplate redisTemplate;

    /** Redis 健康状态（带 3 秒探测间隔，避免抖动导致频繁切换） */
    private volatile boolean redisHealthy = true;
    private volatile long lastRedisCheckTime = 0;

    /** 防重占位 TTL（秒）：活动期内足够长，防止用户中途重复提交 */
    @Value("${ecommerce.seckill.dedup-ttl:3600}")
    private int dedupTtl;

    /** 消费幂等标记 TTL（秒）：消息重投窗口之外的重复可忽略 */
    @Value("${ecommerce.seckill.consumed-ttl:86400}")
    private int consumedTtl;

    /** 补偿任务扫描「未落单流水」的时间阈值（秒） */
    @Value("${ecommerce.seckill.settle-timeout:30}")
    private int settleTimeout;

    /** 消息最大重试次数，超过进死信队列 */
    @Value("${ecommerce.seckill.max-retry:3}")
    private int maxRetry;

    // ==================== 同步链路 ====================

    @Override
    public SeckillResultVO seckill(SeckillRequestDTO dto) {
        Long userId = UserContextHolder.requireUserId();
        int qty = dto.getQuantity() == null ? 1 : dto.getQuantity();

        // ---- 1. 活动校验 ----
        SeckillActivity activity = activityMapper.selectById(dto.getActivityId());
        if (activity == null || activity.getStatus() != BizConst.ACTIVITY_RUNNING) {
            return SeckillResultVO.notStarted();
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            return SeckillResultVO.notStarted();
        }
        if (now.isAfter(activity.getEndTime())) {
            return SeckillResultVO.ended();
        }

        int bucketCount = activity.getBucketCount() == null || activity.getBucketCount() <= 0
                ? 10 : activity.getBucketCount();

        // ---- 2. 限流（第一道防线）----
        // 放在最前面：被限流的请求连防重和库存逻辑都不该进，
        // 这些请求注定要失败，让它们消耗 DB 连接和线程是纯浪费。
        String limitedBy = rateLimiter.check(activity.getId(), userId, currentIp());
        if (limitedBy != null) {
            log.debug("请求被限流: dimension={}, user={}, activity={}",
                    limitedBy, userId, activity.getId());
            return SeckillResultVO.rateLimited();
        }

        // ---- 3. Redis 可用性探测 + 降级决策 ----
        if (!redisAvailable()) {
            // Redis 挂了就直接走 DB 路径：不预扣、不入队，同步落单。
            // 吞吐会掉到千级，但正确性由 DB 条件更新保证，不超卖。
            log.warn("Redis 不可用，降级到 DB 直连模式: activity={}", activity.getId());
            return degradedSeckill(activity, dto, userId, qty);
        }

        // ---- 4. Redis 防重（第一层幂等）----
        // 放在库存扣减之前：重复请求没必要再走一遍库存逻辑
        if (!redisUtil.tryDedup(activity.getId(), dto.getSkuId(), userId, dedupTtl)) {
            return SeckillResultVO.duplicated();
        }

        // ---- 3. Redis Lua 原子预扣（前置过滤）----
        int bucket = ThreadLocalRandom.current().nextInt(bucketCount);
        long deductResult = redisUtil.deductStock(
                activity.getId(), dto.getSkuId(), bucket, qty);

        if (deductResult == -1) {
            // Redis 库存未初始化，尝试兜底初始化一次
            initActivityStock(activity.getId());
            deductResult = redisUtil.deductStock(activity.getId(), dto.getSkuId(), bucket, qty);
        }

        if (deductResult == 0) {
            // 本桶已空，尝试换桶（最多试 3 次）
            deductResult = tryOtherBuckets(activity.getId(), dto.getSkuId(), bucketCount, qty, bucket);
        }

        if (deductResult != 1) {
            // 预扣失败：释放防重让用户可以重试（比如换活动时段再来）
            redisUtil.releaseDedup(activity.getId(), dto.getSkuId(), userId);
            return SeckillResultVO.soldOut();
        }

        // ---- 4. 记录预扣流水 + 入队 ----
        String preDeductNo = BizUtil.generateOrderNo(userId);
        SeckillPreDeduct flow = new SeckillPreDeduct();
        flow.setPreDeductNo(preDeductNo);
        flow.setActivityId(activity.getId());
        flow.setSkuId(dto.getSkuId());
        flow.setUserId(userId);
        flow.setBucketIndex(bucket);
        flow.setQuantity(qty);
        flow.setDirection(1);
        flow.setStatus(0);
        flow.setCreateTime(now);
        flow.setUpdateTime(now);
        preDeductMapper.insert(flow);

        // 消息体用 JSON 传递，显式携带所有消费端需要的字段，
        // 消费端不再回查 Redis，保证消息自包含
        String message = buildMessage(preDeductNo, activity.getId(), dto.getSkuId(),
                userId, bucket, qty, dto.getRequestId());
        redisTemplate.opsForList().leftPush(SeckillRedisKey.queueKey(), message);

        log.debug("秒杀请求入队: preDeductNo={}, user={}, sku={}",
                preDeductNo, userId, dto.getSkuId());

        int remaining = redisUtil.totalRedisStock(activity.getId(), dto.getSkuId(), bucketCount);
        SeckillResultVO vo = SeckillResultVO.queued();
        vo.setRemaining(remaining);
        vo.setOrderNo(preDeductNo);
        return vo;
    }

    /**
     * 降级路径：Redis 不可用时直接同步落库。
     *
     * <p><b>为什么这样降级是安全的</b>：DB 的条件更新
     * {@code UPDATE ... WHERE available >= qty} 本身就能防超卖，
     * Redis 预扣只是性能优化。拿掉它只是慢，不会错。</p>
     *
     * <p><b>代价</b>：每个请求同步打 DB，吞吐从万级掉到千级。
     * 所以必须配合限流——限流此时是保护 DB 的最后一道闸。</p>
     */
    private SeckillResultVO degradedSeckill(SeckillActivity activity,
                                            SeckillRequestDTO dto,
                                            Long userId, int qty) {
        try {
            // 生成占位流水号，降级路径没有 Redis 预扣，但仍要写流水以保持对账口径一致
            String preDeductNo = BizUtil.generateOrderNo(userId);
            boolean ok = createOrderTransactional(preDeductNo, activity.getId(),
                    dto.getSkuId(), userId, 0, qty, dto.getRequestId());
            if (ok) {
                SeckillOrder order = orderMapper.selectByPreDeductNo(preDeductNo);
                SeckillResultVO vo = SeckillResultVO.success(
                        order == null ? preDeductNo : order.getOrderNo(),
                        order == null ? null : order.getId());
                vo.setDegraded(true);
                return vo;
            }
            // 落单返回 false = 重复下单（唯一索引冲突），
            // createOrderTransactional 已把异常消化掉并做了回补
            return SeckillResultVO.duplicated();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return SeckillResultVO.duplicated();
        } catch (Exception e) {
            log.error("降级下单失败: activity={}, user={}", activity.getId(), userId, e);
            return SeckillResultVO.busy();
        }
    }

    /**
     * Redis 可用性探测（带缓存，避免每次请求都探）。
     * <p>用「读一个固定 key」探测而不是 PING：PING 不涉及数据结构，
     * 部分故障下 PING 正常但读写异常。</p>
     */
    private boolean redisAvailable() {
        // 3 秒内连续失败才判定不可用，避免网络抖动导致频繁切换降级模式
        if (System.currentTimeMillis() - lastRedisCheckTime < 3000) {
            return redisHealthy;
        }
        try {
            redisTemplate.opsForValue().get(SeckillRedisKey.degradeKey());
            if (!redisHealthy) {
                log.info("Redis 已恢复，切回正常模式");
            }
            redisHealthy = true;
        } catch (Exception e) {
            if (redisHealthy) {
                log.error("Redis 不可用，秒杀降级到 DB 直连: {}", e.getMessage());
            }
            redisHealthy = false;
        }
        lastRedisCheckTime = System.currentTimeMillis();
        return redisHealthy;
    }

    /** 获取当前请求 IP（供 IP 维度限流使用） */
    private String currentIp() {
        try {
            HttpServletRequest req = ((ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes()).getRequest();
            String ip = req.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(ip)) {
                // X-Forwarded-For 可能是 "client, proxy1, proxy2"，取第一个
                return ip.split(",")[0].trim();
            }
            ip = req.getHeader("X-Real-IP");
            if (StringUtils.hasText(ip)) {
                return ip;
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 本桶售罄时换桶重试。
     * <p>分桶的已知代价：可能「桶已空但总量还有」，此时会误判售罄。
     * 换桶能缓解，但不能完全消除——这是分桶方案对精确性的妥协，
     * 换来的是 Redis 不成为单点瓶颈。</p>
     */
    private long tryOtherBuckets(Long activityId, Long skuId, int bucketCount, int qty, int current) {
        for (int i = 0; i < 3 && i < bucketCount; i++) {
            int idx = ThreadLocalRandom.current().nextInt(bucketCount);
            if (idx == current) {
                continue;
            }
            long r = redisUtil.deductStock(activityId, skuId, idx, qty);
            if (r == 1) {
                return 1;
            }
        }
        return 0;
    }

    private String buildMessage(String preDeductNo, Long activityId, Long skuId,
                                Long userId, int bucket, int qty, String requestId) {
        return String.join("|", preDeductNo,
                String.valueOf(activityId), String.valueOf(skuId), String.valueOf(userId),
                String.valueOf(bucket), String.valueOf(qty), requestId);
    }

    // ==================== 异步链路 ====================

    @Override
    public int consumePendingOrders(int batchSize) {
        return consumeBatch(batchSize, 1);
    }

    /**
     * 多消费者并发消费。
     *
     * <p>每个消费者用 {@code RPOPLPUSH} 原子取消息 —— 多个消费者不会拿到同一条，
     * 这是「消费者集群」能正确工作的前提。用 LRANGE + LREM 会让 N 个消费者
     * 同时读到队首消息，靠幂等兜住不超卖但白白浪费 N-1 份 CPU 和 DB 连接。</p>
     *
     * <p>单条消息的处理是独立的：一个失败不影响其他，失败的那条走重试/死信。</p>
     *
     * @param batchSize 每轮最多消费多少条
     * @param consumers 并发消费者数
     * @return 成功落单数
     */
    @Override
    public int consumeBatch(int batchSize, int consumers) {
        int perConsumer = Math.max(batchSize / Math.max(consumers, 1), 1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(consumers);
        java.util.concurrent.atomic.AtomicInteger success =
                new java.util.concurrent.atomic.AtomicInteger(0);
        try {
            for (int i = 0; i < consumers; i++) {
                pool.submit(() -> {
                    for (int n = 0; n < perConsumer; n++) {
                        // RPOPLPUSH：原子取走并留存位点
                        String msg = redisUtil.takeMessage(
                                SeckillRedisKey.queueKey(),
                                SeckillRedisKey.processingQueueKey());
                        if (msg == null) {
                            return;   // 队列空了
                        }
                        handleOneMessage(msg, success);
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        int n = success.get();
        if (n > 0) {
            log.info("秒杀消费完成: 并发={}, 落单={}", consumers, n);
        }
        return n;
    }

    /**
     * 处理单条消息：成功 ack，失败重试，超限进死信。
     */
    private void handleOneMessage(String message,
                                  java.util.concurrent.atomic.AtomicInteger successCounter) {
        String processingKey = SeckillRedisKey.processingQueueKey();
        String queueKey = SeckillRedisKey.queueKey();
        String deadKey = SeckillRedisKey.deadLetterQueueKey();
        try {
            if (doConsume(message)) {
                redisUtil.ackMessage(processingKey, message);
                successCounter.incrementAndGet();
            } else {
                // 业务性失败（如 DB 库存已耗尽）：doConsume 内部已做 Redis 回补，
                // 直接 ack 掉，不需要重试——重试也不会成功
                redisUtil.ackMessage(processingKey, message);
            }
        } catch (Exception e) {
            String preDeductNo = message.split("\\|")[0];
            long retry = incrementRetry(preDeductNo);
            if (retry >= maxRetry) {
                // 超过重试上限：进死信队列。
                // 不能无限重试——FIFO 语义下队头一条坏消息会把整个队列堵死。
                log.error("消息重试超限，移入死信队列: no={}, retries={}", preDeductNo, retry);
                redisUtil.moveToDeadLetter(processingKey, deadKey, message);
                // 清掉幂等标记，允许补偿任务重新处理
                redisTemplate.delete(SeckillRedisKey.consumedKey(preDeductNo));
            } else {
                log.warn("消息消费失败，将重试: no={}, retry={}/{}", preDeductNo, retry, maxRetry);
                redisUtil.nackMessage(queueKey, processingKey, message);
            }
        }
    }

    /** 累加重试次数（带 TTL，自动过期清理） */
    private long incrementRetry(String preDeductNo) {
        try {
            String key = SeckillRedisKey.consumeRetryKey(preDeductNo);
            Long v = redisTemplate.opsForValue().increment(key);
            if (v != null && v == 1L) {
                redisTemplate.expire(key, java.time.Duration.ofHours(1));
            }
            return v == null ? 1L : v;
        } catch (Exception e) {
            return maxRetry;   // Redis 异常时直接进死信，不无限重试
        }
    }

    /**
     * 消费单条消息：DB 条件更新扣库存 + 写订单。
     *
     * <p>三层幂等，从快到慢：</p>
     * <ol>
     *   <li>Redis 标记 preDeductNo 已消费（最快，拦掉绝大多数重复投递）</li>
     *   <li>DB 查 pre_deduct_no 是否已落单（过滤同状态重入）</li>
     *   <li>DB 唯一索引 uk_user_activity_sku（<b>唯一可靠的最终兜底</b>）</li>
     * </ol>
     */
    private boolean doConsume(String message) {
        String[] parts = message.split("\\|");
        if (parts.length < 6) {
            log.warn("消息格式非法，直接丢弃: {}", message);
            return false;
        }
        String preDeductNo = parts[0];
        Long activityId = Long.valueOf(parts[1]);
        Long skuId = Long.valueOf(parts[2]);
        Long userId = Long.valueOf(parts[3]);
        int bucket = Integer.parseInt(parts[4]);
        int qty = Integer.parseInt(parts[5]);
        String requestId = parts.length > 6 ? parts[6] : "";

        // ---- 幂等第一层：Redis 标记 ----
        // 注意：标记放在事务外。若事务失败要主动清除标记，否则消息重投会被误拦。
        if (!redisUtil.tryMarkConsumed(preDeductNo, consumedTtl)) {
            log.debug("消息已消费过，跳过: {}", preDeductNo);
            return true;
        }

        try {
            boolean ok = createOrderTransactional(preDeductNo, activityId, skuId,
                    userId, bucket, qty, requestId);
            if (!ok) {
                // 落单失败（重复下单或库存不足）：清除幂等标记，
                // 并抛异常让消息进入重试/死信流程。
                // <b>不能静默返回 false</b>——handleOneMessage 会把 false 当作
                // 「已处理」而 ack 掉，消息就此丢失，用户永远拿不到结果。
                redisTemplate.delete(SeckillRedisKey.consumedKey(preDeductNo));
                throw new BusinessException("秒杀落单失败（重复下单或库存不足）");
            }
            return true;
        } catch (BusinessException e) {
            redisTemplate.delete(SeckillRedisKey.consumedKey(preDeductNo));
            throw e;
        } catch (Exception e) {
            redisTemplate.delete(SeckillRedisKey.consumedKey(preDeductNo));
            throw e;
        }
    }

    /**
     * 落库：委托给 {@link SeckillOrderCreator}。
     * <p>本方法<b>不能</b>标 {@code @Transactional}——它是同类内部调用，
     * Spring AOP 代理不会生效，事务形同虚设。真正的落单方法已抽到独立 Bean。</p>
     * <p>这里额外负责「DB 扣减失败时回补 Redis」，让两边账目保持一致。</p>
     */
    private boolean createOrderTransactional(String preDeductNo, Long activityId, Long skuId,
                                            Long userId, int bucket, int qty, String requestId) {
        try {
            return orderCreator.create(preDeductNo, activityId, skuId, userId, qty, requestId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一索引冲突 = 同一用户重复下单。
            // 此时 SeckillOrderCreator 内部事务已回滚，库存扣减已撤销，数据一致。
            log.warn("唯一索引冲突（重复下单）: user={}, activity={}, sku={}",
                    userId, activityId, skuId);
            safeRestoreFlow(preDeductNo);
            safeReleaseDedup(activityId, skuId, userId);
            return false;
        } catch (Exception e) {
            // 落单失败：回补 Redis 预扣，否则库存被永久占用
            log.error("秒杀落单失败，回补 Redis 预扣: preDeductNo={}", preDeductNo, e);
            safeRestoreRedisStock(activityId, skuId, bucket, qty);
            safeRestoreFlow(preDeductNo);
            safeReleaseDedup(activityId, skuId, userId);
            throw e;
        }
    }

    /**
     * 标记预扣流水为已回补（DB 操作，必须成功）。
     */
    private void safeRestoreFlow(String preDeductNo) {
        try {
            restorePreDeductFlow(preDeductNo);
        } catch (Exception e) {
            // 流水状态错了会被对账任务捞出来再处理一次，不阻断主流程
            log.error("回补预扣流水失败，将由对账任务兜底: no={}", preDeductNo, e);
        }
    }

    /**
     * 释放防重占位，Redis 不可用时静默跳过。
     * <p><b>降级路径下 Redis 本来就是挂的</b>，这里绝不能抛异常——
     * 否则会掩盖真正的业务结果，让用户看到「系统繁忙」而不是「已售罄」。</p>
     */
    private void safeReleaseDedup(Long activityId, Long skuId, Long userId) {
        try {
            redisUtil.releaseDedup(activityId, skuId, userId);
        } catch (Exception e) {
            log.debug("Redis 不可用，跳过释放防重（降级路径属预期情况）");
        }
    }

    /** 回补 Redis 库存，Redis 不可用时静默跳过 */
    private void safeRestoreRedisStock(Long activityId, Long skuId, int bucket, int qty) {
        try {
            SeckillStock stock = stockMapper.selectByActivityAndSku(activityId, skuId);
            if (stock != null) {
                restoreRedisStock(activityId, skuId, bucket, qty, stock.getTotalStock());
            }
        } catch (Exception e) {
            log.debug("Redis 不可用，跳过回补 Redis 库存（降级路径属预期情况）");
        }
    }

    /** 回补 Redis 库存（DB 侧扣减失败时保持两边一致） */
    private void restoreRedisStock(Long activityId, Long skuId, int bucket,
                                   int qty, int totalStock) {
        int bucketCount = redisBucketCount(activityId);
        int capacity = totalStock / Math.max(bucketCount, 1);
        redisUtil.restoreStock(activityId, skuId, bucket, qty, capacity);
    }

    private int redisBucketCount(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        return activity == null || activity.getBucketCount() == null
                ? 10 : activity.getBucketCount();
    }

    private void restorePreDeductFlow(String preDeductNo) {
        preDeductMapper.markRestored(preDeductNo);
    }

    // ==================== 订单操作 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long orderId) {
        Long userId = UserContextHolder.requireUserId();
        SeckillOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        int rows = orderMapper.markPaid(orderId, LocalDateTime.now());
        if (rows == 0) {
            throw new BusinessException("订单状态不允许支付");
        }
        // 锁定转已售
        stockMapper.confirmSold(order.getActivityId(), order.getSkuId(), order.getQuantity());
        log.info("秒杀订单支付成功: orderNo={}", order.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        Long userId = UserContextHolder.requireUserId();
        SeckillOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        int rows = orderMapper.cancelOrder(orderId, LocalDateTime.now());
        if (rows == 0) {
            // 状态机拦截：已支付或已取消过，不重复回补库存
            throw new BusinessException("订单状态不允许取消");
        }
        // 释放锁定 + 回补可用库存
        stockMapper.releaseLock(order.getActivityId(), order.getSkuId(), order.getQuantity());
        stockMapper.restoreAvailable(order.getActivityId(), order.getSkuId(), order.getQuantity());
        log.info("秒杀订单已取消，库存已回补: orderNo={}", order.getOrderNo());
    }

    @Override
    public List<SeckillOrder> listMyOrders(Long userId) {
        return orderMapper.selectByUser(userId, 20);
    }

    @Override
    public SeckillResultVO queryResult(String requestId) {
        Long userId = UserContextHolder.requireUserId();
        // 按用户查最近订单（requestId 未建索引，用用户维度查最近的即可）
        List<SeckillOrder> orders = orderMapper.selectByUser(userId, 10);
        for (SeckillOrder o : orders) {
            if (requestId.equals(o.getRequestId())) {
                SeckillResultVO vo = SeckillResultVO.success(o.getOrderNo(), o.getId());
                vo.setCode(BizConst.SECKILL_ORDER_PAID);
                return vo;
            }
        }
        return SeckillResultVO.queued();
    }

    // ==================== 管理与运维 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initActivityStock(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException("活动不存在");
        }
        // 查该活动下所有 SKU 的库存记录
        List<SeckillStock> stocks = stockMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillStock>()
                        .eq(SeckillStock::getActivityId, activityId));
        for (SeckillStock stock : stocks) {
            String flagKey = SeckillRedisKey.initFlagKey(activityId, stock.getSkuId());
            // 初始化标记防止覆盖已售库存。
            // 用 Duration 而非秒数：setIfAbsent(value, timeout) 的第二参是 Duration
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(flagKey, "1", java.time.Duration.ofHours(1));
            if (Boolean.TRUE.equals(isNew)) {
                redisUtil.initStock(activityId, stock.getSkuId(),
                        stock.getTotalStock(), activity.getBucketCount());
            }
        }
        log.info("活动库存初始化完成: activity={}", activityId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetActivity(Long activityId) {
        // 清 Redis 缓存
        redisUtil.clearActivity(activityId);
        // 恢复 DB 库存
        List<SeckillStock> stocks = stockMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillStock>()
                        .eq(SeckillStock::getActivityId, activityId));
        for (SeckillStock stock : stocks) {
            // 复合主键必须用专用方法，updateById 会跨 SKU 串改
            stockMapper.resetStock(activityId, stock.getSkuId(), stock.getTotalStock());
        }
        // 清预扣流水
        preDeductMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillPreDeduct>()
                        .eq(SeckillPreDeduct::getActivityId, activityId));
        // 清订单：<b>必须一并清掉</b>。残留订单会让对账的
        // 「已下单量 == 总库存 - 可用库存」永远不成立，压测结果失真。
        // 删除顺序有外键约束：先删明细（t_seckill_order_item 引用 t_seckill_order），
        // 顺序反了会报 SQLIntegrityConstraintViolationException。
        // 注意 in() 传空列表会生成非法 SQL（IN ()），必须先判空。
        List<Long> orderIds = orderMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillOrder>()
                                .select(SeckillOrder::getId)
                                .eq(SeckillOrder::getActivityId, activityId))
                .stream().map(SeckillOrder::getId).toList();
        if (!orderIds.isEmpty()) {
            orderItemMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillOrderItem>()
                            .in(SeckillOrderItem::getOrderId, orderIds));
        }
        orderMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getActivityId, activityId));
        log.info("活动已重置: activity={}（库存已恢复，流水、订单与明细已清空）", activityId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuildActivity(Long activityId, Long skuId, int totalStock, int bucketCount) {
        if (totalStock <= 0) {
            throw new BusinessException("库存必须大于 0");
        }
        // 先重置（含清订单、清流水）
        resetActivity(activityId);

        // 改活动分桶数
        SeckillActivity act = activityMapper.selectById(activityId);
        if (act != null) {
            act.setBucketCount(bucketCount);
            activityMapper.updateById(act);
        }

        // 改库存总量并重置库存状态（复合主键走专用 SQL，不能用 updateById）
        SeckillStock exist = stockMapper.selectByActivityAndSku(activityId, skuId);
        if (exist == null) {
            throw new BusinessException("该活动下没有该商品的库存记录");
        }
        stockMapper.updateStockTotal(activityId, skuId, totalStock);
        stockMapper.resetStock(activityId, skuId, totalStock);

        // 同步活动总库存
        if (act != null) {
            act.setTotalStock(totalStock);
            activityMapper.updateById(act);
        }

        // 重新初始化 Redis 库存
        redisUtil.initStock(activityId, skuId, totalStock, bucketCount);
        log.info("活动已重建: activity={}, sku={}, totalStock={}, buckets={}",
                activityId, skuId, totalStock, bucketCount);
    }

    @Override
    public Map<String, Object> getStockDetail(Long activityId, Long skuId) {
        Map<String, Object> result = new HashMap<>();
        SeckillStock stock = stockMapper.selectByActivityAndSku(activityId, skuId);
        result.put("dbStock", stock);
        SeckillActivity activity = activityMapper.selectById(activityId);
        int bucketCount = activity == null || activity.getBucketCount() == null
                ? 10 : activity.getBucketCount();
        result.put("bucketCount", bucketCount);
        result.put("redisTotal", redisUtil.totalRedisStock(activityId, skuId, bucketCount));
        List<Integer> buckets = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            Integer v = redisUtil.getStock(activityId, skuId, i);
            buckets.add(v == null ? 0 : v);
        }
        result.put("redisBuckets", buckets);
        result.put("queueLength", redisUtil.queueLength(SeckillRedisKey.queueKey()));
        result.put("validOrderCount", orderMapper.countValidOrders(activityId));
        return result;
    }

    /**
     * 对账：校准 Redis 与 DB，输出可核对的报告。
     *
     * <p><b>核心不变量</b>：{@code available + locked + sold == totalStock}。
     * 这条不成立就说明库存账目乱了，是判定「有无超卖」的硬指标。</p>
     */
    @Override
    public Map<String, Object> reconcile(Long activityId) {
        Map<String, Object> report = new HashMap<>();
        SeckillActivity activity = activityMapper.selectById(activityId);
        int bucketCount = activity == null || activity.getBucketCount() == null
                ? 10 : activity.getBucketCount();

        List<SeckillStock> stocks = stockMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillStock>()
                        .eq(SeckillStock::getActivityId, activityId));

        List<Map<String, Object>> details = new ArrayList<>();
        boolean allBalanced = true;
        int netFlow = preDeductMapper.countNetPreDeduct(activityId);

        for (SeckillStock stock : stocks) {
            Map<String, Object> item = new HashMap<>();
            int available = nvl(stock.getAvailable());
            int locked = nvl(stock.getLocked());
            int sold = nvl(stock.getSold());
            int total = nvl(stock.getTotalStock());

            // 不变量 1：DB 内部账目平衡
            boolean dbBalanced = (available + locked + sold == total);

            int redisStock = redisUtil.totalRedisStock(activityId, stock.getSkuId(), bucketCount);
            int dbUsed = total - available;

            // 不变量 2：DB 剩余 + 已下单 == 总库存
            int validOrders = countValidOrdersBySku(activityId, stock.getSkuId());
            boolean orderBalanced = (dbUsed == validOrders);

            item.put("skuId", stock.getSkuId());
            item.put("totalStock", total);
            item.put("available", available);
            item.put("locked", locked);
            item.put("sold", sold);
            item.put("redisStock", redisStock);
            item.put("validOrders", validOrders);
            item.put("dbBalanced", dbBalanced);
            item.put("orderBalanced", orderBalanced);
            item.put("matched", dbBalanced && orderBalanced);

            if (!dbBalanced || !orderBalanced) {
                allBalanced = false;
            }
            details.add(item);
        }

        report.put("activityId", activityId);
        report.put("allBalanced", allBalanced);
        report.put("totalStock", activity == null ? 0 : activity.getTotalStock());
        report.put("validOrders", orderMapper.countValidOrders(activityId));
        report.put("netPreDeduct", netFlow);
        report.put("details", details);
        report.put("conclusion", allBalanced
                ? "账目平衡，无超卖"
                : "存在账目差异，需人工介入排查");
        return report;
    }

    private int countValidOrdersBySku(Long activityId, Long skuId) {
        List<SeckillOrder> all = orderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getActivityId, activityId)
                        .eq(SeckillOrder::getSkuId, skuId)
                        .in(SeckillOrder::getStatus, BizConst.SECKILL_ORDER_UNPAID,
                                BizConst.SECKILL_ORDER_PAID));
        int sum = 0;
        for (SeckillOrder o : all) {
            sum += nvl(o.getQuantity());
        }
        return sum;
    }

    /**
     * 补偿：回补「已预扣但未落单」的库存。
     * <p>这批记录就是「Redis 扣了但订单没成」的部分，是异步架构的固有漏洞，
     * 必须有兜底。状态机守卫保证重复执行不会把库存越补越多。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int compensateUnsettled(Long activityId, int batchSize) {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(settleTimeout);
        List<SeckillPreDeduct> flows = preDeductMapper.selectUnsettledBefore(deadline, batchSize);

        int compensated = 0;
        for (SeckillPreDeduct flow : flows) {
            // 状态守卫：只有 0（已预扣未落单）才能改成 2（已回补）
            if (preDeductMapper.markRestored(flow.getPreDeductNo()) == 0) {
                continue;
            }
            // 确认真的没落单（防止消息还在队列里没被消费）
            SeckillOrder exist = orderMapper.selectByPreDeductNo(flow.getPreDeductNo());
            if (exist != null) {
                // 已经落单了，把状态改回已落单
                preDeductMapper.markOrdered(flow.getPreDeductNo(), exist.getId());
                continue;
            }
            // 回补 DB
            stockMapper.restoreAvailable(flow.getActivityId(), flow.getSkuId(), flow.getQuantity());
            // 回补 Redis
            int bucketCount = redisBucketCount(flow.getActivityId());
            SeckillStock stock = stockMapper.selectByActivityAndSku(
                    flow.getActivityId(), flow.getSkuId());
            int capacity = stock == null ? Integer.MAX_VALUE
                    : stock.getTotalStock() / Math.max(bucketCount, 1);
            redisUtil.restoreStock(flow.getActivityId(), flow.getSkuId(),
                    flow.getBucketIndex(), flow.getQuantity(), capacity);
            // 释放防重，允许用户重新参与
            redisUtil.releaseDedup(flow.getActivityId(), flow.getSkuId(), flow.getUserId());
            compensated++;
        }
        if (compensated > 0) {
            log.warn("秒杀库存补偿完成: activity={}, 补偿 {} 条未落单预扣", activityId, compensated);
        }
        return compensated;
    }

    private int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    // ==================== 消费者运维 ====================

    @Override
    public int recoverStale(int limit) {
        return redisUtil.recoverStaleMessages(
                SeckillRedisKey.processingQueueKey(),
                SeckillRedisKey.queueKey(), limit);
    }

    @Override
    public int requeueDeadLetters(int limit) {
        var dead = redisTemplate.opsForList().range(
                SeckillRedisKey.deadLetterQueueKey(), 0, limit - 1L);
        if (dead == null || dead.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String msg : dead) {
            if (redisUtil.ackMessage(SeckillRedisKey.deadLetterQueueKey(), msg) > 0) {
                redisTemplate.opsForList().leftPush(SeckillRedisKey.queueKey(), msg);
                n++;
            }
        }
        log.warn("死信重投 {} 条回主队列", n);
        return n;
    }

    @Override
    public Map<String, Object> getMetrics(Long activityId, Long skuId) {
        Map<String, Object> m = new HashMap<>();

        // 队列积压：三个队列的长度都要监控
        long pending = redisUtil.queueLength(SeckillRedisKey.queueKey());
        long processing = redisUtil.queueLength(SeckillRedisKey.processingQueueKey());
        long dead = redisUtil.deadLetterCount();
        m.put("queuePending", pending);
        m.put("queueProcessing", processing);
        m.put("queueDeadLetter", dead);
        m.put("redisHealthy", redisHealthy);

        // 限流配置
        m.put("rateLimit", rateLimiter.getConfig());

        if (activityId != null && skuId != null) {
            SeckillStock stock = stockMapper.selectByActivityAndSku(activityId, skuId);
            if (stock != null) {
                int available = nvl(stock.getAvailable());
                int locked = nvl(stock.getLocked());
                int sold = nvl(stock.getSold());
                int total = nvl(stock.getTotalStock());
                m.put("stock", stock);
                m.put("available", available);
                m.put("locked", locked);
                m.put("sold", sold);
                m.put("totalStock", total);

                // ===== 告警判定 =====
                List<Map<String, Object>> alerts = new ArrayList<>();

                // 告警 1：库存为负 —— 严重超卖，必须立即告警
                if (available < 0) {
                    alerts.add(alert("CRITICAL", "库存为负，发生超卖",
                            "available=" + available));
                }
                // 告警 2：账目不守恒
                if (available + locked + sold != total) {
                    alerts.add(alert("CRITICAL", "库存账目不守恒",
                            available + "+" + locked + "+" + sold + " != " + total));
                }
                // 告警 3：队列积压 —— 消费能力跟不上
                if (pending > 10000) {
                    alerts.add(alert("WARN", "消息队列积压严重",
                            "pending=" + pending));
                }
                // 告警 4：死信堆积
                if (dead > 0) {
                    alerts.add(alert("WARN", "存在消费失败的消息",
                            "deadLetter=" + dead));
                }
                // 告警 5：处理中队列积压 —— 可能有消费者卡死
                if (processing > 5000) {
                    alerts.add(alert("WARN", "处理中队列积压，消费者可能异常",
                            "processing=" + processing));
                }
                // 告警 6：Redis 降级中
                if (!redisHealthy) {
                    alerts.add(alert("WARN", "Redis 不可用，已降级到 DB 直连",
                            "吞吐大幅下降"));
                }
                m.put("alerts", alerts);
                m.put("healthy", alerts.isEmpty());
            }
        }
        return m;
    }

    private Map<String, Object> alert(String level, String title, String detail) {
        Map<String, Object> a = new HashMap<>();
        a.put("level", level);
        a.put("title", title);
        a.put("detail", detail);
        a.put("time", LocalDateTime.now().toString());
        return a;
    }
}