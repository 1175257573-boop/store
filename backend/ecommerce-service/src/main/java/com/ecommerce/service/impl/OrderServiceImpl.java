package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.constant.RedisKey;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.util.BizUtil;
import com.ecommerce.dao.entity.Address;
import com.ecommerce.dao.entity.CartItem;
import com.ecommerce.dao.entity.Order;
import com.ecommerce.dao.entity.OrderItem;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.mapper.CartItemMapper;
import com.ecommerce.dao.mapper.OrderItemMapper;
import com.ecommerce.dao.mapper.OrderMapper;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.service.AddressService;
import com.ecommerce.service.OrderService;
import com.ecommerce.service.dto.OrderCreateDTO;
import com.ecommerce.service.util.RedisCacheUtil;
import com.ecommerce.service.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单服务实现。
 * <p><b>防超卖策略</b>：不依赖 Redis 预扣，而是用
 * {@code UPDATE t_product SET stock = stock - ? WHERE id = ? AND stock >= ?}
 * 这条带条件的原子更新，影响行数为 0 即代表库存已被并发请求抢空。
 * 相比「Redis 预扣 + 异步落库」，它与订单写在同一个事务里，不存在预扣成功但订单失败的超卖窗口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final CartItemMapper cartItemMapper;
    private final AddressService addressService;
    private final RedisCacheUtil cacheUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(OrderCreateDTO dto) {
        Long userId = UserContextHolder.requireUserId();
        boolean fromCart = !"buyNow".equals(dto.getSource());

        // ---------- 1. 组装待下单商品 ----------
        List<ItemLine> lines = new ArrayList<>();
        List<Long> cartIdsToClean = new ArrayList<>();

        if (fromCart) {
            List<CartItem> cartItems = cartItemMapper.selectCheckedByUserId(userId);
            if (cartItems.isEmpty()) {
                throw new BusinessException(ResultCode.ORDER_EMPTY);
            }
            List<Long> productIds = cartItems.stream().map(CartItem::getProductId).distinct().toList();
            Map<Long, Product> productMap = loadProducts(productIds);
            for (CartItem ci : cartItems) {
                Product p = productMap.get(ci.getProductId());
                if (p == null || p.getStatus() != BizConst.PRODUCT_ON_SHELF) {
                    throw new BusinessException(ResultCode.PRODUCT_NOT_EXIST);
                }
                lines.add(new ItemLine(p, ci.getQuantity()));
                cartIdsToClean.add(ci.getId());
            }
        } else {
            List<OrderCreateDTO.BuyNowItem> items = dto.getItems();
            List<Long> productIds = items.stream().map(OrderCreateDTO.BuyNowItem::getProductId)
                    .distinct().toList();
            Map<Long, Product> productMap = loadProducts(productIds);
            for (OrderCreateDTO.BuyNowItem item : items) {
                Product p = productMap.get(item.getProductId());
                if (p == null || p.getStatus() != BizConst.PRODUCT_ON_SHELF) {
                    throw new BusinessException(ResultCode.PRODUCT_NOT_EXIST);
                }
                lines.add(new ItemLine(p, item.getQuantity()));
            }
        }

        // ---------- 2. 收货地址 ----------
        Address address = addressService.getById(dto.getAddressId());
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_EXIST);
        }
        String fullAddress = address.getProvince() + address.getCity()
                + address.getDistrict() + address.getDetail();

        // ---------- 3. 逐条扣库存（条件更新防超卖） ----------
        // 先扣库存再算钱：任何一条扣减失败即抛异常，事务回滚把所有扣减还回去
        for (ItemLine line : lines) {
            int rows = productMapper.deductStock(line.product.getId(), line.quantity);
            if (rows == 0) {
                throw new BusinessException(
                        String.format("商品「%s」库存不足，当前仅剩 %d 件",
                                line.product.getName(),
                                line.product.getStock() == null ? 0 : line.product.getStock()));
            }
        }

        // ---------- 4. 计算金额并落订单 ----------
        BigDecimal totalAmount = lines.stream()
                .map(l -> l.product.getPrice().multiply(BigDecimal.valueOf(l.quantity)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = new Order();
        order.setOrderNo(BizUtil.generateOrderNo(userId));
        order.setUserId(userId);
        // 商家ID取第一件商品的归属：同一订单若跨商家，商家端需按明细过滤
        order.setMerchantId(lines.get(0).product.getMerchantId());
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setStatus(BizConst.ORDER_UNPAID);
        // 地址信息存快照：用户日后改地址不影响历史订单
        order.setReceiver(address.getReceiver());
        order.setPhone(address.getPhone());
        order.setAddress(fullAddress);
        order.setRemark(dto.getRemark());
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        baseMapper.insert(order);

        // ---------- 5. 落订单明细 ----------
        List<OrderItem> orderItems = new ArrayList<>();
        for (ItemLine line : lines) {
            OrderItem oi = new OrderItem();
            oi.setOrderId(order.getId());
            oi.setOrderNo(order.getOrderNo());
            oi.setProductId(line.product.getId());
            // 明细上也冗余商家ID：商家端按商品维度统计销量时无需 join 订单表
            oi.setMerchantId(line.product.getMerchantId());
            oi.setProductName(line.product.getName());
            oi.setProductImage(line.product.getMainImage());
            oi.setProductPrice(line.product.getPrice());
            oi.setQuantity(line.quantity);
            oi.setSubtotal(line.product.getPrice().multiply(BigDecimal.valueOf(line.quantity)));
            orderItems.add(oi);
        }
        // 逐条插入：走 BaseMapper 的 insert，明细条数少（单笔订单一般 1-5 项），
        // 用 IService.insertBatch 需注入 OrderItemService，此处直接用 Mapper 更轻
        for (OrderItem oi : orderItems) {
            orderItemMapper.insert(oi);
        }

        // ---------- 6. 清理购物车 ----------
        if (fromCart && !cartIdsToClean.isEmpty()) {
            cartItemMapper.deleteByIds(userId, cartIdsToClean);
        }

        // ---------- 7. 商品缓存失效（价格/库存可能已变） ----------
        lines.forEach(l -> cacheUtil.delete(RedisKey.PRODUCT_DETAIL + l.product.getId()));

        log.info("订单创建成功: orderNo={}, userId={}, amount={}, items={}",
                order.getOrderNo(), userId, totalAmount, lines.size());
        return OrderVO.from(order, orderItems);
    }

    @Override
    public IPage<OrderVO> pageMyOrders(int pageNum, int pageSize, Integer status) {
        Long userId = UserContextHolder.requireUserId();
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        int safeNum = Math.max(pageNum, 1);

        Page<Order> page = this.page(new Page<>(safeNum, safeSize),
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .eq(status != null, Order::getStatus, status)
                        .orderByDesc(Order::getCreateTime));

        Page<OrderVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
                .map(o -> OrderVO.from(o, orderItemMapper.selectByOrderId(o.getId())))
                .collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public OrderVO getOrderDetail(Long orderId) {
        Long userId = UserContextHolder.requireUserId();
        Order order = this.getOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId)
                .last("LIMIT 1"));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_EXIST);
        }
        return OrderVO.from(order, orderItemMapper.selectByOrderId(orderId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        Long userId = UserContextHolder.requireUserId();
        Order order = getOwnOrder(orderId, userId);
        // 只有待付款可取消
        if (order.getStatus() != BizConst.ORDER_UNPAID) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }
        // 带原状态条件更新：若被并发请求先改过则影响 0 行
        int rows = baseMapper.updateStatus(orderId, BizConst.ORDER_UNPAID,
                BizConst.ORDER_CANCELLED, LocalDateTime.now());
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }
        // 回补库存
        List<OrderItem> items = orderItemMapper.selectByOrderId(orderId);
        items.forEach(item -> {
            productMapper.restoreStock(item.getProductId(), item.getQuantity());
            // 库存变了，商品详情缓存必须失效，否则详情页仍显示旧库存
            cacheUtil.delete(RedisKey.PRODUCT_DETAIL + item.getProductId());
        });
        log.info("订单已取消: orderNo={}", order.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long orderId) {
        Long userId = UserContextHolder.requireUserId();
        Order order = getOwnOrder(orderId, userId);
        if (order.getStatus() == BizConst.ORDER_PAID) {
            throw new BusinessException(ResultCode.ORDER_ALREADY_PAID);
        }
        if (order.getStatus() != BizConst.ORDER_UNPAID) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }
        baseMapper.updateStatus(orderId, BizConst.ORDER_UNPAID, BizConst.ORDER_PAID, LocalDateTime.now());
        log.info("订单支付成功: orderNo={}", order.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceipt(Long orderId) {
        Long userId = UserContextHolder.requireUserId();
        Order order = getOwnOrder(orderId, userId);
        if (order.getStatus() != BizConst.ORDER_SHIPPED) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }
        baseMapper.updateStatus(orderId, BizConst.ORDER_SHIPPED,
                BizConst.ORDER_FINISHED, LocalDateTime.now());
    }

    @Override
    public Map<String, Integer> countByStatus() {
        Long userId = UserContextHolder.requireUserId();
        Map<String, Integer> result = new HashMap<>();
        for (int s = 0; s <= 4; s++) {
            result.put("status" + s, 0);
        }
        for (Map<String, Object> row : baseMapper.countByStatus(userId)) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            result.put("status" + status, cnt == null ? 0 : Integer.parseInt(String.valueOf(cnt)));
        }
        return result;
    }

    /** 取当前用户名下订单，不存在则抛异常（同时防越权访问他人订单） */
    private Order getOwnOrder(Long orderId, Long userId) {
        Order order = this.getOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId)
                .last("LIMIT 1"));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_EXIST);
        }
        return order;
    }

    /** 批量查商品 */
    private Map<Long, Product> loadProducts(List<Long> ids) {
        return productMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
    }

    /** 内部记录类：商品 + 购买数量 */
    private record ItemLine(Product product, Integer quantity) {
    }
}
