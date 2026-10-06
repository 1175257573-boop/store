package com.ecommerce.api.task;

import com.ecommerce.dao.mapper.SeckillActivityMapper;
import com.ecommerce.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 秒杀后台任务。
 *
 * <p>承担三件事，都是「兜底」性质——正常情况下不该被触发，
 * 但架构上的每个漏洞都需要有自愈手段：</p>
 * <ol>
 *   <li>消费待下单消息（生产环境由 MQ 消费者替代）</li>
 *   <li>补偿未落单的预扣（回补库存）</li>
 *   <li>结束过期活动</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillScheduledTask {

    private final SeckillService seckillService;
    private final SeckillActivityMapper activityMapper;

    @Value("${ecommerce.seckill.consume-batch:200}")
    private int consumeBatch;

    @Value("${ecommerce.seckill.task-enabled:false}")
    private boolean taskEnabled;

    @Value("${ecommerce.seckill.consumers:8}")
    private int consumers;

    /**
     * 消费待下单消息。
     * <p>开发环境用 Redis 队列 + 多线程模拟消费者集群；
     * 生产环境应换成真正的 MQ 消费者，消费成功后手动 ACK。</p>
     */
    @Scheduled(fixedDelayString = "${ecommerce.seckill.consume-interval:200}")
    public void consumeOrders() {
        if (!taskEnabled) {
            return;
        }
        try {
            int n = seckillService.consumeBatch(consumeBatch, consumers);
            if (n > 0) {
                log.info("定时消费完成: 落单 {} 条", n);
            }
        } catch (Exception e) {
            log.error("定时消费异常", e);
        }
    }

    /**
     * 恢复僵死消息。
     *
     * <p>消费者在「已从队列取走、尚未 ack」时崩溃，消息会卡在处理中队列。
     * 没有这个任务，订单会永久丢失，表现为「用户排队中但永远不出结果」。</p>
     */
    @Scheduled(fixedDelayString = "${ecommerce.seckill.recover-interval:30000}")
    public void recoverStale() {
        if (!taskEnabled) {
            return;
        }
        try {
            int n = seckillService.recoverStale(1000);
            if (n > 0) {
                log.warn("恢复僵死消息 {} 条", n);
            }
        } catch (Exception e) {
            log.error("恢复僵死消息异常", e);
        }
    }

    /**
     * 补偿「已预扣但未落单」的预扣。
     * <p>这是异步架构的必备兜底：Redis 扣了但订单没落成时，
     * 库存会被永久占用，表现为「明明有货却买不到」。</p>
     */
    @Scheduled(fixedDelayString = "${ecommerce.seckill.compensate-interval:10000}")
    public void compensate() {
        if (!taskEnabled) {
            return;
        }
        try {
            activityMapper.selectAll().forEach(activity -> {
                int n = seckillService.compensateUnsettled(activity.getId(), 200);
                if (n > 0) {
                    log.warn("库存补偿: activity={}, 处理 {} 条", activity.getId(), n);
                }
            });
        } catch (Exception e) {
            log.error("库存补偿任务异常", e);
        }
    }

    /** 结束过期活动 */
    @Scheduled(fixedDelayString = "${ecommerce.seckill.activity-check:60000}")
    public void finishExpiredActivities() {
        try {
            int n = activityMapper.finishExpiredActivities(LocalDateTime.now());
            if (n > 0) {
                log.info("已结束 {} 个过期活动", n);
            }
        } catch (Exception e) {
            log.error("活动状态更新异常", e);
        }
    }
}
