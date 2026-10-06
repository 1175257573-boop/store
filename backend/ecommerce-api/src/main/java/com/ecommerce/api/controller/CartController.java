package com.ecommerce.api.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.service.CartService;
import com.ecommerce.service.dto.CartAddDTO;
import com.ecommerce.service.vo.CartItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
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

/**
 * 购物车接口（需登录）。
 */
@Tag(name = "03-购物车", description = "加购、改数量、勾选、删除")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@Validated
public class CartController {

    private final CartService cartService;

    @Operation(summary = "查询我的购物车")
    @GetMapping
    public Result<List<CartItemVO>> list() {
        return Result.success(cartService.listMyCart());
    }

    @Operation(summary = "加入购物车", description = "已存在同款商品则累加数量")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody CartAddDTO dto) {
        cartService.addToCart(dto);
        return Result.success("已加入购物车", null);
    }

    @Operation(summary = "修改购买数量")
    @PutMapping("/{id}")
    public Result<Void> updateQuantity(@PathVariable Long id,
                                       @RequestParam @Min(1) @Max(999) Integer quantity) {
        cartService.updateQuantity(id, quantity);
        return Result.success("数量已更新", null);
    }

    @Operation(summary = "切换勾选状态")
    @PutMapping("/{id}/checked")
    public Result<Void> updateChecked(@PathVariable Long id,
                                       @RequestParam @Min(0) @Max(1) Integer checked) {
        cartService.updateChecked(id, checked);
        return Result.success();
    }

    @Operation(summary = "全选 / 全不选")
    @PutMapping("/checked-all")
    public Result<Void> updateAllChecked(@RequestParam @Min(0) @Max(1) Integer checked) {
        cartService.updateAllChecked(checked);
        return Result.success();
    }

    @Operation(summary = "删除购物车条目")
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        cartService.removeFromCart(id);
        return Result.success("已移除", null);
    }

    @Operation(summary = "批量删除购物车条目")
    @DeleteMapping("/batch")
    public Result<Void> removeBatch(@RequestBody List<Long> ids) {
        cartService.removeBatch(ids);
        return Result.success("已批量移除", null);
    }

    @Operation(summary = "已勾选商品件数", description = "用于顶部购物车角标")
    @GetMapping("/count")
    public Result<Integer> count() {
        return Result.success(cartService.countChecked());
    }
}