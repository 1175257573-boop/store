package com.ecommerce.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ecommerce.common.result.Result;
import com.ecommerce.service.OrderService;
import com.ecommerce.service.dto.OrderCreateDTO;
import com.ecommerce.service.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订单接口（需登录）。
 */
@Tag(name = "05-订单", description = "下单、支付、取消、收货")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "提交订单",
            description = "source=cart 从购物车结算（自动清理已结算条目）；"
                    + "source=buyNow 立即购买（需带 items）")
    @PostMapping
    public Result<OrderVO> create(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success("下单成功", orderService.createOrder(dto));
    }

    @Operation(summary = "查询我的订单", description = "status 为空表示查询全部")
    @GetMapping("/list")
    public Result<IPage<OrderVO>> list(@RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "10") int pageSize,
                                       @RequestParam(required = false) Integer status) {
        return Result.success(orderService.pageMyOrders(pageNum, pageSize, status));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.success(orderService.getOrderDetail(id));
    }

    @Operation(summary = "取消订单", description = "仅待付款可取消，取消后自动回补库存")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancelOrder(id);
        return Result.success("订单已取消", null);
    }

    @Operation(summary = "支付订单", description = "演示环境为模拟支付，未接入真实支付渠道")
    @PostMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Long id) {
        orderService.payOrder(id);
        return Result.success("支付成功", null);
    }

    @Operation(summary = "确认收货", description = "仅已发货订单可确认")
    @PostMapping("/{id}/confirm")
    public Result<Void> confirm(@PathVariable Long id) {
        orderService.confirmReceipt(id);
        return Result.success("已确认收货", null);
    }

    @Operation(summary = "各状态订单数量统计", description = "用于个人中心订单角标")
    @GetMapping("/count")
    public Result<Map<String, Integer>> count() {
        return Result.success(orderService.countByStatus());
    }
}