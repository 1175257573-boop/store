package com.ecommerce.service.impl;

import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.util.BizUtil;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.entity.SeckillOrder;
import com.ecommerce.dao.entity.SeckillOrderItem;
import com.ecommerce.dao.entity.SeckillPreDeduct;
import com.ecommerce.dao.entity.SeckillStock;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.dao.mapper.SeckillOrderItemMapper;
import com.ecommerce.dao.mapper.SeckillOrderMapper;
import com.ecommerce.dao.mapper.SeckillPreDeductMapper;
import com.ecommerce.dao.mapper.SeckillStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀落单事务边界。
 *
 * <p><b>为什么必须独立成一个 Bean</b>：Spring 的 {@code @Transactional}
 * 基于 AOP 代理实现，<b>同类内部方法调用不经过代理，事务完全不生效</b>。
 * 如果把 {@code @Transactional} 方法留在 SeckillServiceImpl 里，
 * 再由 seckill() / doConsume() 直接调用，看起来有事务、实际没有——
 * 一旦 insert 抛唯一索引冲突，前面已扣的库存不会回滚，库存凭空少掉。</p>
 *
 * <p>这个坑很隐蔽：代码里有 {@code @Transactional} 注解，运行也不报错，
 * 只有对账时才发现账目不平。压测里「重复请求导致库存少 1」就是这么测出来的。</p>
 *
 * <p>另外用 {@link Propagation#REQUIRES_NEW} 显式声明「必须新事务」，
 * 双重保险：即便将来被别的事务调用，也能保证独立提交或回滚。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderCreator {

    private final SeckillStockMapper stockMapper;
    private final SeckillOrderMapper orderMapper;
    private final SeckillPreDeductMapper preDeductMapper;
    private final ProductMapper productMapper;
    private final SeckillOrderItemMapper seckillOrderItemMapper;

    /**
     * 创建秒杀订单（扣库存 + 写订单 + 写明细，原子完成）。
     *
     * @return true=落单成功；false=库存不足或已落单（业务性失败，不抛异常）
     * @throws org.springframework.dao.DuplicateKeyException
     *         唯一索引冲突（同一用户重复下单），调用方转为「重复提交」提示。
     *         <b>此异常会触发整个事务回滚，已扣的库存会被撤销</b>，
     *         这是保证「重复请求不丢库存」的关键。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean create(String preDeductNo, Long activityId, Long skuId,
                          Long userId, int qty, String requestId) {
        // ---- 幂等第二层：DB 查该预扣流水是否已落单 ----
        // 查流水表而不是订单表：同一 preDeductNo 只会生成一个订单，
        // 流水的 status=1 就代表已落单
        SeckillPreDeduct flow = preDeductMapper.selectByNo(preDeductNo);
        if (flow != null && flow.getStatus() != null
                && flow.getStatus() == BizConst.PREDEDUCT_ORDERED) {
            log.debug("该预扣已落单，跳过: {}", preDeductNo);
            return true;
        }

        SeckillStock stock = stockMapper.selectByActivityAndSku(activityId, skuId);
        if (stock == null) {
            log.error("秒杀库存记录不存在: activity={}, sku={}", activityId, skuId);
            return false;
        }

        // ---- 最终正确性裁决：DB 条件更新 ----
        // Redis 预扣只是过滤器，DB 才是权威。影响 0 行即库存已耗尽。
        int deducted = stockMapper.deductAvailable(activityId, skuId, qty);
        if (deducted == 0) {
            log.warn("DB 库存不足，本次落单失败: activity={}, sku={}, qty={}",
                    activityId, skuId, qty);
            return false;
        }

        Product product = productMapper.selectById(skuId);
        if (product == null) {
            // 抛异常触发回滚：库存扣减必须撤销，否则会少卖
            throw new BusinessException("商品不存在");
        }

        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(BizUtil.generateOrderNo(userId));
        order.setActivityId(activityId);
        order.setSkuId(skuId);
        order.setUserId(userId);
        order.setQuantity(qty);
        order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(qty)));
        order.setStatus(BizConst.SECKILL_ORDER_UNPAID);
        order.setRequestId(requestId);
        order.setPreDeductNo(preDeductNo);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());

        // 唯一索引 uk_user_activity_sku 是幂等的最终兜底。
        // 冲突时抛异常 -> 整个事务回滚（含上面的库存扣减）-> 数据一致
        orderMapper.insert(order);

        // 锁定库存：标记为「已下单未支付」
        stockMapper.lockStock(activityId, skuId, qty);

        // 预扣流水标记为已落单
        preDeductMapper.markOrdered(preDeductNo, order.getId());

        // 写订单明细（历史订单展示依赖它）
        // 注意用 SeckillOrderItemMapper 而非普通订单的 OrderItemMapper：
        // t_order_item 的外键指向 t_order，秒杀订单在 t_seckill_order，混用会外键失败
        SeckillOrderItem item = new SeckillOrderItem();
        item.setOrderId(order.getId());
        item.setOrderNo(order.getOrderNo());
        item.setProductId(skuId);
        item.setProductName(product.getName());
        item.setProductImage(product.getMainImage());
        item.setProductPrice(product.getPrice());
        item.setQuantity(qty);
        item.setSubtotal(order.getAmount());
        seckillOrderItemMapper.insert(item);

        log.info("秒杀下单成功: orderNo={}, user={}, sku={}, amount={}",
                order.getOrderNo(), userId, skuId, order.getAmount());
        return true;
    }
}
