package com.ecommerce.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ecommerce.common.result.Result;
import com.ecommerce.dao.entity.Category;
import com.ecommerce.service.MerchantService;
import com.ecommerce.service.ProductService;
import com.ecommerce.service.vo.ProductDetailVO;
import com.ecommerce.service.vo.ProductVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品接口（全部公开，无需登录）。
 */
@Tag(name = "02-商品浏览", description = "商品列表、搜索、详情、分类")
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final MerchantService merchantService;

    @Operation(summary = "分页查询商品",
            description = "支持分类筛选、关键字搜索、四种排序；无需登录")
    @GetMapping("/list")
    public Result<IPage<ProductVO>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "12") int pageSize,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "default") String sortBy) {
        return Result.success(productService.pageProducts(pageNum, pageSize, categoryId, keyword, sortBy));
    }

    @Operation(summary = "查询商品详情", description = "每次访问浏览量 +1")
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(productService.getDetail(id));
    }

    @Operation(summary = "相关推荐", description = "同分类下的其他在售商品")
    @GetMapping("/{id}/related")
    public Result<List<ProductVO>> related(@PathVariable Long id,
                                           @RequestParam(defaultValue = "6") int limit) {
        return Result.success(productService.listRelated(id, limit));
    }

    @Operation(summary = "查询所有商品分类")
    @GetMapping("/categories")
    public Result<List<Category>> categories() {
        return Result.success(productService.listCategories());
    }

    @Operation(summary = "查询店铺公开信息", description = "商品页展示店铺名与评分用")
    @GetMapping("/shop/{merchantId}")
    public Result<com.ecommerce.service.vo.ShopPublicVO> shopInfo(
            @PathVariable Long merchantId) {
        return Result.success(merchantService.getPublicShopInfo(merchantId));
    }
}