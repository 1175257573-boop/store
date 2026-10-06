package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.dao.entity.Order;
import com.ecommerce.service.dto.OrderCreateDTO;
import com.ecommerce.service.vo.OrderVO;

import java.util.List;
import java.util.Map;

/**
 * 订单服务接口。
 */
public interface OrderService extends IService<Order> {

    /**
     * 提交订单。
     * <p>内部流程：校验商品与库存 → 逐条扣库存（条件更新防超卖）→ 落订单主表与明细
     * → 清理已结算的购物车条目。整体在一个事务内，任一步失败则全部回滚。</p>
     *
     * @return 生成的订单 VO
     */
    OrderVO createOrder(OrderCreateDTO dto);

    /**
     * 分页查询我的订单。
     *
     * @param status 订单状态，null 表示全部
     */
    com.baomidou.mybatisplus.core.metadata.IPage<OrderVO> pageMyOrders(int pageNum, int pageSize, Integer status);

    /**
     * 订单详情。
     */
    OrderVO getOrderDetail(Long orderId);

    /**
     * 取消订单，同时回补库存。
     */
    void cancelOrder(Long orderId);

    /**
     * 模拟支付（演示用，无真实支付渠道）。
     */
    void payOrder(Long orderId);

    /**
     * 确认收货。
     */
    void confirmReceipt(Long orderId);

    /**
     * 各状态订单数量统计，用于个人中心角标。
     */
    Map<String, Integer> countByStatus();
}