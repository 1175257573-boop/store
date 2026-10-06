package com.ecommerce.api.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.dao.entity.AfterSale;
import com.ecommerce.dao.entity.Merchant;
import com.ecommerce.dao.entity.MerchantApply;
import com.ecommerce.dao.entity.Order;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.entity.ProductSku;
import com.ecommerce.service.MerchantDashboardService;
import com.ecommerce.service.MerchantOrderService;
import com.ecommerce.service.MerchantProductService;
import com.ecommerce.service.MerchantService;
import com.ecommerce.service.dto.AfterSaleApplyDTO;
import com.ecommerce.service.dto.MerchantApplyDTO;
import com.ecommerce.service.dto.MerchantProductDTO;
import com.ecommerce.service.dto.MerchantUpdateDTO;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商家中心接口。
 *
 * <p>路径以 {@code /api/merchant} 开头。数据隔离在 Service 层完成：
 * 所有「查自己数据」的 SQL 都带 {@code merchant_id} 条件，
 * 商家即使拿到别人的 ID 也查不到、也改不了。</p>
 */
@Tag(name = "07-商家中心", description = "入驻开店、商品管理、订单发货、售后处理、数据看板")
@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;
    private final MerchantProductService productService;
    private final MerchantOrderService orderService;
    private final MerchantDashboardService dashboardService;

    // ==================== 入驻与店铺 ====================

    @Operation(summary = "提交入驻申请", description = "审核通过后自动创建店铺并升级为商家角色")
    @PostMapping("/apply")
    public Result<Long> apply(@Valid @RequestBody MerchantApplyDTO dto) {
        return Result.success("申请已提交，请等待审核", merchantService.apply(dto));
    }

    @Operation(summary = "查我的入驻申请", description = "含被拒绝原因")
    @GetMapping("/apply/my")
    public Result<MerchantApply> myApply() {
        return Result.success(merchantService.getMyApply());
    }

    @Operation(summary = "撤销入驻申请")
    @PostMapping("/apply/{id}/revoke")
    public Result<Void> revokeApply(@PathVariable Long id) {
        merchantService.revokeApply(id);
        return Result.success("申请已撤销", null);
    }

    @Operation(summary = "我的店铺信息")
    @GetMapping("/shop")
    public Result<Merchant> shop() {
        return Result.success(merchantService.getShopInfo());
    }

    @Operation(summary = "修改店铺信息", description = "资质类字段不可自行修改")
    @PutMapping("/shop")
    public Result<Void> updateShop(@RequestBody MerchantUpdateDTO dto) {
        merchantService.updateShop(dto);
        return Result.success("店铺信息已更新", null);
    }

    // ==================== 平台管理端 ====================

    @Operation(summary = "入驻申请列表（管理员）")
    @GetMapping("/admin/applies")
    public Result<List<MerchantApply>> listApplies(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.success(merchantService.listApplies(status, keyword));
    }

    @Operation(summary = "审核入驻申请（管理员）",
            description = "通过则自动建店 + 用户角色升为商家，三步在同一事务内完成")
    @PostMapping("/admin/apply/{id}/audit")
    public Result<Void> auditApply(@PathVariable Long id,
                                   @RequestParam boolean pass,
                                   @RequestParam(required = false) String remark) {
        merchantService.audit(id, pass, remark);
        return Result.success(pass ? "已通过审核" : "已拒绝", null);
    }

    @Operation(summary = "管理员待办数量（侧边栏红点）",
            description = "一次返回入驻审核与商品审核的待审数量，避免两次请求")
    @GetMapping("/admin/todo")
    public Result<Map<String, Integer>> adminTodo() {
        return Result.success(merchantService.getAdminTodoCount());
    }

    @Operation(summary = "店铺列表（管理员）")
    @GetMapping("/admin/shops")
    public Result<List<Merchant>> listShops(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.success(merchantService.listShops(status, keyword));
    }

    @Operation(summary = "冻结/解冻店铺（管理员）",
            description = "冻结后自动下架其全部商品")
    @PostMapping("/admin/shop/{id}/status")
    public Result<Void> changeShopStatus(@PathVariable Long id,
                                         @RequestParam Integer status) {
        merchantService.changeShopStatus(id, status);
        return Result.success("店铺状态已更新", null);
    }

    // ==================== 商品管理 ====================

    @Operation(summary = "发布商品", description = "新建商品进审核队列，审核通过后由商家上架")
    @PostMapping("/product")
    public Result<Long> createProduct(@Valid @RequestBody MerchantProductDTO dto) {
        return Result.success("商品已提交审核", productService.create(dto));
    }

    @Operation(summary = "编辑商品", description = "编辑后重置为待审核，防止先审核后偷改")
    @PutMapping("/product/{id}")
    public Result<Void> updateProduct(@PathVariable Long id,
                                       @Valid @RequestBody MerchantProductDTO dto) {
        productService.update(id, dto);
        return Result.success("商品已更新，等待重新审核", null);
    }

    @Operation(summary = "删除商品", description = "有销量的商品不能删除，只能下架")
    @DeleteMapping("/product/{id}")
    public Result<Void> deleteProduct(@PathVariable Long id) {
        productService.delete(id);
        return Result.success("商品已删除", null);
    }

    @Operation(summary = "上架/下架商品")
    @PutMapping("/product/{id}/status")
    public Result<Void> changeProductStatus(@PathVariable Long id,
                                            @RequestParam Integer status) {
        productService.changeStatus(id, status);
        return Result.success(status == 1 ? "商品已上架" : "商品已下架", null);
    }

    @Operation(summary = "我的商品列表", description = "只返回本店的商品")
    @GetMapping("/product/list")
    public Result<List<Product>> myProducts(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer auditStatus,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword) {
        return Result.success(productService.listMyProducts(
                status, auditStatus, categoryId, keyword));
    }

    @Operation(summary = "商品详情（含 SKU 与规格）")
    @GetMapping("/product/{id}")
    public Result<Map<String, Object>> productDetail(@PathVariable Long id) {
        return Result.success(productService.getDetail(id));
    }

    @Operation(summary = "商品的 SKU 列表")
    @GetMapping("/product/{id}/skus")
    public Result<List<ProductSku>> productSkus(@PathVariable Long id) {
        return Result.success(productService.listSkus(id));
    }

    @Operation(summary = "修改 SKU 价格与库存")
    @PutMapping("/product/{productId}/sku/{skuId}")
    public Result<Void> updateSku(@PathVariable Long productId,
                                  @PathVariable Long skuId,
                                  @RequestParam BigDecimal price,
                                  @RequestParam Integer stock,
                                  @RequestParam(required = false) String skuCode,
                                  @RequestParam(required = false) Integer status) {
        productService.updateSku(productId, skuId, price, stock, skuCode, status);
        return Result.success("SKU 已更新", null);
    }

    @Operation(summary = "我的商品数统计")
    @GetMapping("/product/count")
    public Result<Map<String, Integer>> productCount() {
        return Result.success(productService.countMyProducts());
    }

    // ==================== 商品审核（管理员）====================

    @Operation(summary = "待审核商品列表（管理员）")
    @GetMapping("/admin/audits")
    public Result<List<Product>> pendingAudits(
            @RequestParam(required = false) String keyword) {
        return Result.success(productService.listPendingAudits(keyword));
    }

    @Operation(summary = "审核商品（管理员）", description = "通过后商家可自行上架")
    @PostMapping("/admin/product/{id}/audit")
    public Result<Void> auditProduct(@PathVariable Long id,
                                     @RequestParam boolean pass,
                                     @RequestParam(required = false) String reason) {
        productService.audit(id, pass, reason);
        return Result.success(pass ? "审核通过" : "已拒绝", null);
    }

    // ==================== 订单发货 ====================

    @Operation(summary = "我的订单列表", description = "只返回本店的订单")
    @GetMapping("/order/list")
    public Result<List<Order>> myOrders(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.success(orderService.listMyOrders(status, keyword));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/order/{id}")
    public Result<Order> orderDetail(@PathVariable Long id) {
        return Result.success(orderService.getOrderDetail(id));
    }

    @Operation(summary = "发货", description = "只有已付款订单可发货，重复点击无效")
    @PostMapping("/order/{id}/ship")
    public Result<Void> ship(@PathVariable Long id,
                             @RequestParam String shipCompany,
                             @RequestParam String shipNo) {
        orderService.ship(id, shipCompany, shipNo);
        return Result.success("发货成功", null);
    }

    @Operation(summary = "订单状态统计（商家角标）")
    @GetMapping("/order/count")
    public Result<Map<String, Integer>> orderCount() {
        return Result.success(orderService.countMyOrders());
    }

    // ==================== 售后 ====================

    @Operation(summary = "申请售后（用户侧）")
    @PostMapping("/after-sale")
    public Result<String> applyAfterSale(@Valid @RequestBody AfterSaleApplyDTO dto) {
        return Result.success("售后申请已提交", orderService.applyAfterSale(dto));
    }

    @Operation(summary = "我的售后申请（用户侧）")
    @GetMapping("/after-sale/my")
    public Result<List<AfterSale>> myAfterSales(
            @RequestParam(required = false) Integer status) {
        return Result.success(orderService.listMyAfterSales(status));
    }

    @Operation(summary = "撤销售后（用户侧）")
    @PostMapping("/after-sale/{id}/revoke")
    public Result<Void> revokeAfterSale(@PathVariable Long id) {
        orderService.revokeAfterSale(id);
        return Result.success("售后申请已撤销", null);
    }

    @Operation(summary = "售后列表（商家端）")
    @GetMapping("/after-sale/list")
    public Result<List<AfterSale>> merchantAfterSales(
            @RequestParam(required = false) Integer status) {
        return Result.success(orderService.listMerchantAfterSales(status));
    }

    @Operation(summary = "售后详情")
    @GetMapping("/after-sale/{id}")
    public Result<AfterSale> afterSaleDetail(@PathVariable Long id) {
        return Result.success(orderService.getAfterSale(id));
    }

    @Operation(summary = "同意售后（商家端）")
    @PostMapping("/after-sale/{id}/approve")
    public Result<Void> approveAfterSale(@PathVariable Long id,
                                         @RequestParam(required = false) String remark) {
        orderService.approveAfterSale(id, remark);
        return Result.success("已同意售后申请", null);
    }

    @Operation(summary = "拒绝售后（商家端）")
    @PostMapping("/after-sale/{id}/reject")
    public Result<Void> rejectAfterSale(@PathVariable Long id,
                                        @RequestParam String remark) {
        orderService.rejectAfterSale(id, remark);
        return Result.success("已拒绝售后申请", null);
    }

    @Operation(summary = "完成退款（商家端）",
            description = "实际打款完成后调用，同时回补库存与店铺销售额")
    @PostMapping("/after-sale/{id}/complete")
    public Result<Void> completeRefund(@PathVariable Long id) {
        orderService.completeRefund(id);
        return Result.success("退款已完成", null);
    }

    @Operation(summary = "售后统计（商家角标）")
    @GetMapping("/after-sale/count")
    public Result<Map<String, Integer>> afterSaleCount() {
        return Result.success(orderService.countMyAfterSales());
    }

    // ==================== 数据看板 ====================

    @Operation(summary = "经营概览", description = "今日/昨日/本月/累计销售额、商品与售后概览")
    @GetMapping("/dashboard/overview")
    public Result<Map<String, Object>> overview() {
        return Result.success(dashboardService.getOverview());
    }

    @Operation(summary = "销售趋势", description = "近 N 天每日销售额与订单数，自动补齐无单日期")
    @GetMapping("/dashboard/trend")
    public Result<Map<String, Object>> trend(@RequestParam(defaultValue = "30") int days) {
        return Result.success(dashboardService.getSalesTrend(days));
    }

    @Operation(summary = "商品销量排行")
    @GetMapping("/dashboard/top")
    public Result<Map<String, Object>> topProducts(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(dashboardService.getTopProducts(limit));
    }

    @Operation(summary = "订单与售后角标")
    @GetMapping("/dashboard/badges")
    public Result<Map<String, Object>> badges() {
        return Result.success(dashboardService.getBadges());
    }
}
