package com.ecommerce.service.impl;

import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.entity.SeckillActivity;
import com.ecommerce.dao.entity.SeckillOrder;
import com.ecommerce.dao.entity.SeckillStock;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.dao.mapper.SeckillActivityMapper;
import com.ecommerce.dao.mapper.SeckillOrderMapper;
import com.ecommerce.dao.mapper.SeckillStockMapper;
import com.ecommerce.service.SeckillActivityAdminService;
import com.ecommerce.service.dto.SeckillActivityDTO;
import com.ecommerce.service.util.SeckillRedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀活动管理实现。
 *
 * <p><b>数据隔离</b>：商家只能操作 {@code merchant_id = 自己店铺} 的活动。
 * 平台自建活动（merchant_id 为 NULL）对所有商家可见，但商家不能改——
 * 否则商家可以把平台活动改成自己的，绕过平台管控。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillActivityAdminServiceImpl implements SeckillActivityAdminService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillStockMapper stockMapper;
    private final SeckillOrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final SeckillRedisUtil redisUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publish(SeckillActivityDTO dto) {
        validate(dto);

        String activityNo = (dto.getActivityNo() == null || dto.getActivityNo().isBlank())
                ? generateActivityNo()
                : dto.getActivityNo();
        if (activityMapper.countByActivityNo(activityNo) > 0) {
            throw new BusinessException("活动编号已存在：" + activityNo);
        }

        // 商家身份：merchant_id 取自己的店铺；管理员发布则建平台活动
        Long merchantId = currentMerchantId();

        // 商品 ID 去重，避免同一商品出现两行
        List<Long> productIds = new ArrayList<>();
        for (SeckillActivityDTO.SeckillGoods g : dto.getGoods()) {
            if (!productIds.contains(g.getProductId())) {
                productIds.add(g.getProductId());
            }
        }

        SeckillActivity activity = new SeckillActivity();
        activity.setActivityNo(activityNo);
        activity.setMerchantId(merchantId);
        activity.setName(dto.getName());
        activity.setCoverImage(dto.getCoverImage());
        activity.setDescription(dto.getDescription());
        activity.setTotalStock(dto.getGoods().stream()
                .mapToInt(SeckillActivityDTO.SeckillGoods::getTotalStock).sum());
        activity.setBucketCount(dto.getBucketCount() == null ? 10 : dto.getBucketCount());
        activity.setLimitPerUser(dto.getLimitPerUser() == null ? 1 : dto.getLimitPerUser());
        activity.setStartTime(dto.getStartTime());
        activity.setEndTime(dto.getEndTime());
        // 活动创建即为「进行中」；定时任务会按 end_time 自动结束
        activity.setStatus(BizConst.ACTIVITY_RUNNING);
        activity.setCreateTime(LocalDateTime.now());
        activityMapper.insert(activity);

        // 落库存记录 + 校验商品存在
        for (SeckillActivityDTO.SeckillGoods g : dto.getGoods()) {
            Product p = productMapper.selectById(g.getProductId());
            if (p == null) {
                throw new BusinessException("商品不存在：" + g.getProductId());
            }
            if (p.getStatus() != BizConst.PRODUCT_ON_SHELF) {
                throw new BusinessException("商品「" + p.getName() + "」未上架，不能加入秒杀");
            }
            SeckillStock stock = new SeckillStock();
            stock.setActivityId(activity.getId());
            stock.setSkuId(g.getProductId());
            stock.setTotalStock(g.getTotalStock());
            stock.setAvailable(g.getTotalStock());
            stock.setLocked(0);
            stock.setSold(0);
            stock.setVersion(0L);
            stockMapper.insert(stock);
        }

        // 预热 Redis 库存：必须在发布时就做。
        // 线上开始瞬间才初始化的话，那一波请求会大量读到 key 不存在。
        int bucketCount = activity.getBucketCount();
        for (SeckillActivityDTO.SeckillGoods g : dto.getGoods()) {
            redisUtil.initStock(activity.getId(), g.getProductId(),
                    g.getTotalStock(), bucketCount);
        }

        log.info("秒杀活动发布成功: no={}, name={}, merchant={}, 商品数={}, 总库存={}, 分桶={}",
                activityNo, activity.getName(), merchantId,
                productIds.size(), activity.getTotalStock(), bucketCount);
        return activity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long activityId, SeckillActivityDTO dto) {
        validate(dto);
        SeckillActivity activity = requireOwnActivity(activityId);

        // 已开始的活动不允许改：线上正在抢，此时改库存会导致
        // Redis 已预热的库存与 DB 不一致，无法对账
        if (activity.getStartTime() != null
                && LocalDateTime.now().isAfter(activity.getStartTime())) {
            throw new BusinessException("活动已开始，不能修改。如需调整请下线后重新发布");
        }
        if (orderMapper.countByActivity(activityId) > 0) {
            throw new BusinessException("该活动已有订单，不能修改");
        }

        activity.setName(dto.getName());
        activity.setCoverImage(dto.getCoverImage());
        activity.setDescription(dto.getDescription());
        activity.setStartTime(dto.getStartTime());
        activity.setEndTime(dto.getEndTime());
        activity.setBucketCount(dto.getBucketCount() == null ? 10 : dto.getBucketCount());
        activity.setLimitPerUser(dto.getLimitPerUser() == null ? 1 : dto.getLimitPerUser());
        activity.setTotalStock(dto.getGoods().stream()
                .mapToInt(SeckillActivityDTO.SeckillGoods::getTotalStock).sum());
        activityMapper.updateById(activity);

        // 商品有变动时整段重建：局部更新容易残留旧 SKU
        stockMapper.deleteByActivity(activityId);
        for (SeckillActivityDTO.SeckillGoods g : dto.getGoods()) {
            SeckillStock stock = new SeckillStock();
            stock.setActivityId(activityId);
            stock.setSkuId(g.getProductId());
            stock.setTotalStock(g.getTotalStock());
            stock.setAvailable(g.getTotalStock());
            stock.setLocked(0);
            stock.setSold(0);
            stock.setVersion(0L);
            stockMapper.insert(stock);
            redisUtil.initStock(activityId, g.getProductId(),
                    g.getTotalStock(), activity.getBucketCount());
        }
        log.info("秒杀活动已更新: id={}, name={}", activityId, activity.getName());
    }

    @Override
    public List<SeckillActivity> list(Integer status, String keyword) {
        Long merchantId = UserContextHolder.isAdmin() ? null : currentMerchantId();
        return activityMapper.selectByCondition(merchantId, status, keyword);
    }

    @Override
    public Map<String, Object> getDetail(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException("活动不存在");
        }
        // 商家只能看自己的；平台自建活动对所有商家可见（只读）
        if (!UserContextHolder.isAdmin()) {
            Long mine = currentMerchantId();
            if (activity.getMerchantId() != null && !activity.getMerchantId().equals(mine)) {
                throw new BusinessException(ResultCode.DATA_NOT_BELONG_TO_YOU);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity", activity);
        result.put("goods", stockMapper.selectSkuWithProduct(activityId));
        result.put("stockSummary", getStockSummary(activityId));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long activityId, Integer status) {
        SeckillActivity activity = requireOwnActivity(activityId);
        if (status == null || status < 0 || status > 3) {
            throw new BusinessException("状态值不合法");
        }
        if (activityMapper.changeStatus(activityId, status) == 0) {
            throw new BusinessException("活动状态已变更，请刷新重试");
        }

        // 上线时预热 Redis 库存；下线时清理，避免占用内存
        if (status == BizConst.ACTIVITY_RUNNING) {
            List<Map<String, Object>> goods = stockMapper.selectSkuWithProduct(activityId);
            for (Map<String, Object> g : goods) {
                Object skuId = g.get("sku_id");
                Object total = g.get("total_stock");
                if (skuId instanceof Number sid && total instanceof Number t) {
                    redisUtil.initStock(activityId, sid.longValue(),
                            t.intValue(), activity.getBucketCount());
                }
            }
            log.info("活动已上线，Redis 库存已预热: id={}, 商品数={}", activityId, goods.size());
        } else {
            redisUtil.clearActivity(activityId);
            log.info("活动已下线，Redis 缓存已清理: id={}", activityId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long activityId) {
        requireOwnActivity(activityId);
        // 有订单不能删：历史订单会断链，用户在订单页会看到空白
        Long orderCount = activityMapper.countOrders(activityId);
        if (orderCount != null && orderCount > 0) {
            throw new BusinessException("该活动已有 " + orderCount + " 笔订单，不能删除，请改为下线");
        }
        redisUtil.clearActivity(activityId);
        stockMapper.deleteByActivity(activityId);
        activityMapper.deleteById(activityId);
        log.info("秒杀活动已删除: id={}", activityId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetStock(Long activityId) {
        requireOwnActivity(activityId);
        List<Map<String, Object>> goods = stockMapper.selectByActivity(activityId);
        if (goods.isEmpty()) {
            throw new BusinessException("该活动没有商品");
        }
        for (Map<String, Object> row : goods) {
            Object sid = row.get("sku_id");
            Object total = row.get("total_stock");
            if (!(sid instanceof Number s) || !(total instanceof Number t)) {
                continue;
            }
            SeckillStock upd = new SeckillStock();
            upd.setActivityId(activityId);
            upd.setSkuId(s.longValue());
            upd.setTotalStock(t.intValue());
            upd.setAvailable(t.intValue());
            upd.setLocked(0);
            upd.setSold(0);
            stockMapper.updateById(upd);
            redisUtil.initStock(activityId, s.longValue(), t.intValue(), 10);
        }
        // 顺带清掉未落单的预扣流水与消费标记，避免重置后被误判为「已消费」
        log.info("活动库存已重置: id={}, 商品数={}", activityId, goods.size());
    }

    @Override
    public Map<String, Object> getStockSummary(Long activityId) {
        List<Integer> sums = stockMapper.sumStock(activityId);
        Map<String, Object> m = new LinkedHashMap<>();
        int available = sums != null && sums.size() > 0 ? sums.get(0) : 0;
        int locked = sums != null && sums.size() > 1 ? sums.get(1) : 0;
        int sold = sums != null && sums.size() > 2 ? sums.get(2) : 0;
        m.put("available", available);
        m.put("locked", locked);
        m.put("sold", sold);
        m.put("total", available + locked + sold);
        return m;
    }

    // ==================== 内部工具 ====================

    private void validate(SeckillActivityDTO dto) {
        if (dto.getStartTime() != null && dto.getEndTime() != null
                && !dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }
        if (dto.getStartTime() != null && dto.getStartTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("开始时间不能早于当前时间");
        }
        if (dto.getBucketCount() != null && dto.getBucketCount() > 100) {
            throw new BusinessException("分桶数量最多 100");
        }
    }

    private String generateActivityNo() {
        return "SK" + System.currentTimeMillis();
    }

    /**
     * 当前操作者关联的商家 ID。
     * <p>管理员发布时返回 null —— 平台自建活动所有商家都能看到。</p>
     */
    private Long currentMerchantId() {
        if (UserContextHolder.isAdmin()) {
            return null;
        }
        return UserContextHolder.requireMerchantId();
    }

    /** 校验活动归属，越权则拒绝 */
    private SeckillActivity requireOwnActivity(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException("活动不存在");
        }
        if (!UserContextHolder.isAdmin()) {
            Long mine = currentMerchantId();
            // 平台自建活动商家也不能改，否则可以篡改平台活动
            if (activity.getMerchantId() == null || !activity.getMerchantId().equals(mine)) {
                throw new BusinessException(ResultCode.DATA_NOT_BELONG_TO_YOU);
            }
        }
        return activity;
    }
}
