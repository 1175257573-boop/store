package com.ecommerce.api.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.dao.entity.SeckillActivity;
import com.ecommerce.dao.entity.SeckillOrder;
import com.ecommerce.service.SeckillActivityAdminService;
import com.ecommerce.service.SeckillService;
import com.ecommerce.service.dto.SeckillActivityDTO;
import com.ecommerce.service.dto.SeckillRequestDTO;
import com.ecommerce.service.vo.SeckillResultVO;
import com.ecommerce.dao.mapper.SeckillActivityMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 秒杀接口。
 */
@Tag(name = "06-秒杀", description = "高并发抢购，Redis 预扣 + DB 条件更新双层校验")
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final SeckillActivityAdminService seckillActivityAdminService;
    private final SeckillActivityMapper activityMapper;

    @Operation(summary = "发起秒杀",
            description = "同步链路只做防重 + Redis 预扣 + 入队，随即返回；订单落库异步完成")
    @PostMapping
    public Result<SeckillResultVO> seckill(@Valid @RequestBody SeckillRequestDTO dto) {
        return Result.success(seckillService.seckill(dto));
    }

    @Operation(summary = "查询抢购结果", description = "前端轮询该接口获取最终订单结果")
    @GetMapping("/result")
    public Result<SeckillResultVO> queryResult(@RequestParam String requestId) {
        return Result.success(seckillService.queryResult(requestId));
    }

    @Operation(summary = "支付秒杀订单")
    @PostMapping("/order/{orderId}/pay")
    public Result<Void> pay(@PathVariable Long orderId) {
        seckillService.payOrder(orderId);
        return Result.success("支付成功", null);
    }

    @Operation(summary = "取消秒杀订单", description = "取消后自动回补库存")
    @PostMapping("/order/{orderId}/cancel")
    public Result<Void> cancel(@PathVariable Long orderId) {
        seckillService.cancelOrder(orderId);
        return Result.success("订单已取消", null);
    }

    @Operation(summary = "我的秒杀订单")
    @GetMapping("/order/my")
    public Result<List<SeckillOrder>> myOrders() {
        Long userId = com.ecommerce.common.context.UserContextHolder.requireUserId();
        return Result.success(seckillService.listMyOrders(userId));
    }

    /* ---------------- 运维接口 ---------------- */

    @Operation(summary = "查询进行中的秒杀活动")
    @GetMapping("/activity/current")
    public Result<SeckillActivity> currentActivity() {
        return Result.success(activityMapper.selectActiveActivity(
                java.time.LocalDateTime.now()));
    }

    @Operation(summary = "查询全部秒杀活动")
    @GetMapping("/activity/list")
    public Result<List<SeckillActivity>> activityList() {
        return Result.success(activityMapper.selectAll());
    }

    @Operation(summary = "查询库存详情", description = "含 DB 库存、Redis 分桶明细、队列长度")
    @GetMapping("/stock")
    public Result<Map<String, Object>> stock(@RequestParam Long activityId,
                                            @RequestParam Long skuId) {
        return Result.success(seckillService.getStockDetail(activityId, skuId));
    }

    @Operation(summary = "初始化活动库存到 Redis")
    @PostMapping("/admin/init/{activityId}")
    public Result<Void> init(@PathVariable Long activityId) {
        seckillService.initActivityStock(activityId);
        return Result.success("库存已初始化", null);
    }

    @Operation(summary = "重置活动（压测前调用）", description = "清缓存 + 恢复 DB 库存 + 清流水与订单")
    @PostMapping("/admin/reset/{activityId}")
    public Result<Void> reset(@PathVariable Long activityId) {
        seckillService.resetActivity(activityId);
        return Result.success("活动已重置", null);
    }

    @Operation(summary = "调整活动库存（压测用）",
            description = "重建活动并指定总库存，用于构造「少量库存 + 高并发」的超卖压力场景")
    @PostMapping("/admin/rebuild")
    public Result<Void> rebuild(@RequestParam Long activityId,
                                @RequestParam Long skuId,
                                @RequestParam int totalStock,
                                @RequestParam(defaultValue = "10") int bucketCount) {
        seckillService.rebuildActivity(activityId, skuId, totalStock, bucketCount);
        return Result.success("活动已重建", null);
    }

    @Operation(summary = "手动触发消费", description = "生产环境由 MQ 消费者替代")
    @PostMapping("/admin/consume")
    public Result<Integer> consume(@RequestParam(defaultValue = "100") int batchSize) {
        return Result.success("消费完成", seckillService.consumePendingOrders(batchSize));
    }

    @Operation(summary = "并发消费（模拟消费者集群）",
            description = "用 RPOPLPUSH 原子取消息，多消费者不重复消费")
    @PostMapping("/admin/consume-cluster")
    public Result<Integer> consumeCluster(
            @RequestParam(defaultValue = "200") int batchSize,
            @RequestParam(defaultValue = "8") int consumers) {
        return Result.success("消费完成", seckillService.consumeBatch(batchSize, consumers));
    }

    @Operation(summary = "恢复僵死消息",
            description = "把处理中队列的消息扫回主队列，用于消费者崩溃后的兜底")
    @PostMapping("/admin/recover")
    public Result<Integer> recover(@RequestParam(defaultValue = "1000") int limit) {
        return Result.success("恢复完成", seckillService.recoverStale(limit));
    }

    @Operation(summary = "死信重投", description = "把死信队列的消息放回主队列重新消费")
    @PostMapping("/admin/requeue")
    public Result<Integer> requeue(@RequestParam(defaultValue = "100") int limit) {
        return Result.success("重投完成", seckillService.requeueDeadLetters(limit));
    }

    @Operation(summary = "运行时监控指标",
            description = "队列积压、死信数、库存状态、告警列表")
    @GetMapping("/admin/metrics")
    public Result<Map<String, Object>> metrics(@RequestParam Long activityId,
                                               @RequestParam Long skuId) {
        return Result.success(seckillService.getMetrics(activityId, skuId));
    }

    @Operation(summary = "对账", description = "校准 Redis 与 DB，输出账目平衡报告")
    @GetMapping("/admin/reconcile")
    public Result<Map<String, Object>> reconcile(@RequestParam Long activityId) {
        return Result.success(seckillService.reconcile(activityId));
    }

    @Operation(summary = "手动触发补偿", description = "回补「已预扣但未落单」的库存")
    @PostMapping("/admin/compensate")
    public Result<Integer> compensate(@RequestParam Long activityId,
                                     @RequestParam(defaultValue = "100") int batchSize) {
        return Result.success("补偿完成", seckillService.compensateUnsettled(activityId, batchSize));
    }

    // ==================== 活动管理（商家 / 管理员）====================

    @Operation(summary = "发布秒杀活动",
            description = "商家发布或管理员创建；内部自动预热 Redis 库存分桶")
    @PostMapping("/admin/publish")
    public Result<Long> publish(@Valid @RequestBody SeckillActivityDTO dto) {
        return Result.success("活动发布成功", seckillActivityAdminService.publish(dto));
    }

    @Operation(summary = "编辑秒杀活动", description = "仅未开始且无订单的活动可编辑")
    @PutMapping("/admin/{activityId}")
    public Result<Void> updateActivity(@PathVariable Long activityId,
                                       @Valid @RequestBody SeckillActivityDTO dto) {
        seckillActivityAdminService.update(activityId, dto);
        return Result.success("活动已更新", null);
    }

    @Operation(summary = "活动列表",
            description = "商家只看自己的活动；管理员看全部")
    @GetMapping("/admin/list")
    public Result<List<SeckillActivity>> activityList(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.success(seckillActivityAdminService.list(status, keyword));
    }

    @Operation(summary = "活动详情", description = "含各商品库存明细与实时汇总")
    @GetMapping("/admin/detail/{activityId}")
    public Result<Map<String, Object>> activityDetail(@PathVariable Long activityId) {
        return Result.success(seckillActivityAdminService.getDetail(activityId));
    }

    @Operation(summary = "活动上线/下线",
            description = "上线时预热 Redis 库存，下线时清理缓存")
    @PostMapping("/admin/{activityId}/status")
    public Result<Void> changeActivityStatus(@PathVariable Long activityId,
                                             @RequestParam Integer status) {
        seckillActivityAdminService.changeStatus(activityId, status);
        return Result.success(status == 1 ? "活动已上线" : "活动已下线", null);
    }

    @Operation(summary = "删除活动", description = "已有订单的活动不能删除")
    @DeleteMapping("/admin/{activityId}")
    public Result<Void> deleteActivity(@PathVariable Long activityId) {
        seckillActivityAdminService.delete(activityId);
        return Result.success("活动已删除", null);
    }

    @Operation(summary = "重置活动库存", description = "压测或活动重开前用")
    @PostMapping("/admin/{activityId}/reset-stock")
    public Result<Void> resetActivityStock(@PathVariable Long activityId) {
        seckillActivityAdminService.resetStock(activityId);
        return Result.success("库存已重置", null);
    }

    @Operation(summary = "活动库存概览", description = "可用 / 锁定 / 已售 / 总计")
    @GetMapping("/admin/{activityId}/stock-summary")
    public Result<Map<String, Object>> stockSummary(@PathVariable Long activityId) {
        return Result.success(seckillActivityAdminService.getStockSummary(activityId));
    }
}