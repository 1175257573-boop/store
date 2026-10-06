package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.CartItem;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.mapper.CartItemMapper;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.service.CartService;
import com.ecommerce.service.dto.CartAddDTO;
import com.ecommerce.service.vo.CartItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 购物车服务实现。
 * <p>购物车落库而非纯 Redis：需要持久化，且条数有限（个位数到几十），
 * 单表查询完全够用，Redis 方案反而要处理序列化与失效一致性。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<CartItemMapper, CartItem> implements CartService {

    private final ProductMapper productMapper;

    @Override
    public List<CartItemVO> listMyCart() {
        Long userId = UserContextHolder.requireUserId();
        List<CartItem> items = baseMapper.selectByUserId(userId);
        if (items.isEmpty()) {
            return List.of();
        }
        // 批量查商品，避免逐条查库（N+1）
        List<Long> productIds = items.stream().map(CartItem::getProductId).distinct().toList();
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        return items.stream()
                .map(item -> CartItemVO.from(item, productMap.get(item.getProductId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addToCart(CartAddDTO dto) {
        Long userId = UserContextHolder.requireUserId();

        Product product = productMapper.selectById(dto.getProductId());
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_EXIST);
        }
        if (product.getStatus() != BizConst.PRODUCT_ON_SHELF) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }

        // 已存在则累加
        CartItem exist = baseMapper.selectOne(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getProductId, dto.getProductId())
                .last("LIMIT 1"));

        int finalQuantity = dto.getQuantity();
        if (exist != null) {
            finalQuantity = exist.getQuantity() + dto.getQuantity();
        }
        if (finalQuantity > BizConst.MAX_BUY_QUANTITY) {
            finalQuantity = BizConst.MAX_BUY_QUANTITY;
        }
        // 加购时同步校验库存，避免把超量商品塞进购物车
        if (product.getStock() != null && finalQuantity > product.getStock()) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH);
        }

        if (exist != null) {
            exist.setQuantity(finalQuantity);
            exist.setUpdateTime(LocalDateTime.now());
            baseMapper.updateById(exist);
        } else {
            CartItem item = new CartItem();
            item.setUserId(userId);
            item.setProductId(dto.getProductId());
            item.setQuantity(finalQuantity);
            item.setChecked(BizConst.YES);
            item.setCreateTime(LocalDateTime.now());
            item.setUpdateTime(LocalDateTime.now());
            baseMapper.insert(item);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQuantity(Long cartId, Integer quantity) {
        Long userId = UserContextHolder.requireUserId();
        if (quantity == null || quantity < 1 || quantity > BizConst.MAX_BUY_QUANTITY) {
            throw new BusinessException(ResultCode.CART_QUITY_INVALID);
        }
        CartItem item = baseMapper.selectOne(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getId, cartId)
                .eq(CartItem::getUserId, userId)
                .last("LIMIT 1"));
        if (item == null) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_EXIST);
        }
        Product product = productMapper.selectById(item.getProductId());
        if (product != null && product.getStock() != null && quantity > product.getStock()) {
            throw new BusinessException("当前库存仅剩 " + product.getStock() + " 件");
        }
        item.setQuantity(quantity);
        item.setUpdateTime(LocalDateTime.now());
        baseMapper.updateById(item);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateChecked(Long cartId, Integer checked) {
        Long userId = UserContextHolder.requireUserId();
        int rows = baseMapper.updateChecked(cartId, userId, checked);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_EXIST);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAllChecked(Integer checked) {
        Long userId = UserContextHolder.requireUserId();
        baseMapper.updateAllChecked(userId, checked);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFromCart(Long cartId) {
        Long userId = UserContextHolder.requireUserId();
        int rows = baseMapper.deleteByIdAndUserId(cartId, userId);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_EXIST);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBatch(List<Long> cartIds) {
        Long userId = UserContextHolder.requireUserId();
        if (cartIds == null || cartIds.isEmpty()) {
            return;
        }
        baseMapper.deleteBatch(userId, cartIds);
    }

    @Override
    public int countChecked() {
        Long userId = UserContextHolder.requireUserId();
        // 返回商品「件数」而非条目数：买 3 件算 3，符合购物车角标的语义
        return baseMapper.selectCheckedByUserId(userId).stream()
                .mapToInt(CartItem::getQuantity)
                .sum();
    }

    @Override
    public List<CartItemVO> listCheckedForOrder() {
        Long userId = UserContextHolder.requireUserId();
        List<CartItem> items = baseMapper.selectCheckedByUserId(userId);
        if (items.isEmpty()) {
            throw new BusinessException(ResultCode.ORDER_EMPTY);
        }
        List<Long> productIds = items.stream().map(CartItem::getProductId).distinct().toList();
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        return items.stream()
                .map(item -> CartItemVO.from(item, productMap.get(item.getProductId())))
                .collect(Collectors.toList());
    }
}