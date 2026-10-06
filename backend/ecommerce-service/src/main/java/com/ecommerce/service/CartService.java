package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.dao.entity.CartItem;
import com.ecommerce.service.dto.CartAddDTO;
import com.ecommerce.service.vo.CartItemVO;

import java.util.List;

/**
 * 购物车服务接口。
 */
public interface CartService extends IService<CartItem> {

    /** 查询当前用户购物车（含商品快照） */
    List<CartItemVO> listMyCart();

    /** 加入购物车，已存在则累加数量 */
    void addToCart(CartAddDTO dto);

    /** 更新购买数量 */
    void updateQuantity(Long cartId, Integer quantity);

    /** 切换勾选状态 */
    void updateChecked(Long cartId, Integer checked);

    /** 全选 / 全不选 */
    void updateAllChecked(Integer checked);

    /** 删除购物车条目 */
    void removeFromCart(Long cartId);

    /** 批量删除 */
    void removeBatch(List<Long> cartIds);

    /** 购物车中已勾选条目数量，用于顶部角标 */
    int countChecked();

    /** 校验并返回用户购物车中已勾选的条目（供下单使用） */
    List<CartItemVO> listCheckedForOrder();
}