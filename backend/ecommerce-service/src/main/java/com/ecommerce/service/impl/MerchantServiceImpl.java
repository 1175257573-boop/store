package com.ecommerce.service.impl;

import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.constant.RoleConst;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.Merchant;
import com.ecommerce.dao.entity.MerchantApply;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.entity.User;
import com.ecommerce.dao.mapper.MerchantApplyMapper;
import com.ecommerce.dao.mapper.MerchantMapper;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.dao.mapper.ProductSkuMapper;
import com.ecommerce.dao.mapper.UserMapper;
import com.ecommerce.service.MerchantService;
import com.ecommerce.service.dto.MerchantApplyDTO;
import com.ecommerce.service.dto.MerchantUpdateDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantServiceImpl implements MerchantService {

    private final MerchantMapper merchantMapper;
    private final MerchantApplyMapper applyMapper;
    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long apply(MerchantApplyDTO dto) {
        Long userId = UserContextHolder.requireUserId();

        // 已开店不能重复申请
        if (merchantMapper.selectByUserId(userId) != null) {
            throw new BusinessException(ResultCode.ALREADY_MERCHANT);
        }
        // 有待审核申请时提示等待，避免刷屏
        MerchantApply pending = applyMapper.selectPendingByUser(userId);
        if (pending != null) {
            throw new BusinessException(ResultCode.APPLY_PENDING);
        }
        // 店铺名全局唯一，重名会让用户以为进错店
        if (merchantMapper.selectByShopName(dto.getShopName()) != null) {
            throw new BusinessException("该店铺名称已被使用，请更换");
        }
        if (applyMapper.selectByCondition(null, dto.getShopName()).stream()
                .anyMatch(a -> dto.getShopName().equals(a.getShopName()))) {
            throw new BusinessException("该店铺名称已被使用，请更换");
        }

        // 企业必须提供营业执照
        if (dto.getBusinessType() != null && dto.getBusinessType() == 2
                && (dto.getLicenseNo() == null || dto.getLicenseNo().isBlank())) {
            throw new BusinessException("企业入驻必须填写营业执照号");
        }

        MerchantApply apply = new MerchantApply();
        apply.setUserId(userId);
        apply.setShopName(dto.getShopName());
        apply.setShopDesc(dto.getShopDesc());
        apply.setContactName(dto.getContactName());
        apply.setContactPhone(dto.getContactPhone());
        apply.setBusinessType(dto.getBusinessType() == null ? 1 : dto.getBusinessType());
        apply.setLicenseNo(dto.getLicenseNo());
        apply.setIdCard(dto.getIdCard());
        apply.setStatus(0);
        apply.setCreateTime(LocalDateTime.now());
        apply.setUpdateTime(LocalDateTime.now());
        applyMapper.insert(apply);

        log.info("商家入驻申请提交: user={}, shop={}", userId, dto.getShopName());
        return apply.getId();
    }

    @Override
    public MerchantApply getMyApply() {
        Long userId = UserContextHolder.requireUserId();
        return applyMapper.selectLatestByUser(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeApply(Long applyId) {
        Long userId = UserContextHolder.requireUserId();
        int rows = applyMapper.revoke(applyId, userId);
        if (rows == 0) {
            throw new BusinessException("申请不存在或已处理，无法撤销");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long applyId, boolean pass, String remark) {
        // 管理员权限
        UserContextHolder.requireAdmin();
        Long auditUserId = UserContextHolder.requireUserId();

        MerchantApply apply = applyMapper.selectById(applyId);
        if (apply == null) {
            throw new BusinessException(ResultCode.APPLY_NOT_EXIST);
        }
        if (apply.getStatus() != null && apply.getStatus() != 0) {
            throw new BusinessException("该申请已处理，不能重复审核");
        }

        if (!pass) {
            if (applyMapper.reject(applyId, auditUserId, remark, LocalDateTime.now()) == 0) {
                throw new BusinessException("审核失败，申请状态已变更");
            }
            log.info("入驻申请被拒绝: apply={}, shop={}", applyId, apply.getShopName());
            return;
        }

        // ---- 审核通过：建店 + 升角色 + 改状态，必须原子完成 ----
        // 三步任一失败都要整体回滚，否则会出现「有店但不是商家」这类脏数据
        if (merchantMapper.selectByShopName(apply.getShopName()) != null) {
            throw new BusinessException("该店铺名称已被占用，无法通过审核");
        }

        Merchant merchant = new Merchant();
        merchant.setUserId(apply.getUserId());
        merchant.setShopName(apply.getShopName());
        merchant.setShopDesc(apply.getShopDesc());
        merchant.setContactName(apply.getContactName());
        merchant.setContactPhone(apply.getContactPhone());
        merchant.setBusinessType(apply.getBusinessType());
        merchant.setLicenseNo(apply.getLicenseNo());
        merchant.setStatus(1);
        merchant.setTotalProduct(0);
        merchant.setTotalOrder(0L);
        merchant.setTotalSales(new java.math.BigDecimal("0.00"));
        merchant.setScore(new java.math.BigDecimal("5.00"));
        merchant.setCreateTime(LocalDateTime.now());
        merchant.setUpdateTime(LocalDateTime.now());
        merchantMapper.insert(merchant);

        // 用户角色升为商家
        User user = new User();
        user.setId(apply.getUserId());
        user.setRole(RoleConst.ROLE_MERCHANT);
        userMapper.updateById(user);

        if (applyMapper.approve(applyId, auditUserId, remark, merchant.getId(),
                LocalDateTime.now()) == 0) {
            throw new BusinessException("审核失败，申请状态已变更");
        }

        log.info("入驻审核通过: apply={}, merchant={}, shop={}",
                applyId, merchant.getId(), merchant.getShopName());
    }

    @Override
    public List<MerchantApply> listApplies(Integer status, String keyword) {
        UserContextHolder.requireAdmin();
        return applyMapper.selectByCondition(status, keyword);
    }

    @Override
    public Merchant getShopInfo() {
        Long merchantId = UserContextHolder.requireMerchantId();
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCode.NOT_MERCHANT_YET);
        }
        return merchant;
    }

    @Override
    public Merchant getShopById(Long merchantId) {
        UserContextHolder.requireAdmin();
        return merchantMapper.selectById(merchantId);
    }

    @Override
    public com.ecommerce.service.vo.ShopPublicVO getPublicShopInfo(Long merchantId) {
        Merchant m = merchantId == null ? null : merchantMapper.selectById(merchantId);
        if (m == null) {
            return null;
        }
        com.ecommerce.service.vo.ShopPublicVO vo = new com.ecommerce.service.vo.ShopPublicVO();
        vo.setId(m.getId());
        vo.setShopName(m.getShopName());
        vo.setShopLogo(m.getShopLogo());
        vo.setShopDesc(m.getShopDesc());
        vo.setScore(m.getScore());
        vo.setOnShelfCount(productMapper.selectByMerchantCondition(
                        m.getId(), null, BizConst.PRODUCT_ON_SHELF, null, null).size());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateShop(MerchantUpdateDTO dto) {
        Long merchantId = UserContextHolder.requireMerchantId();
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCode.NOT_MERCHANT_YET);
        }
        if (dto.getShopName() != null && !dto.getShopName().equals(merchant.getShopName())) {
            // 改名要重新查重，否则会和别人重名
            Merchant same = merchantMapper.selectByShopName(dto.getShopName());
            if (same != null && !same.getId().equals(merchantId)) {
                throw new BusinessException("该店铺名称已被使用，请更换");
            }
            merchant.setShopName(dto.getShopName());
        }
        if (dto.getShopLogo() != null) {
            merchant.setShopLogo(dto.getShopLogo());
        }
        if (dto.getShopDesc() != null) {
            merchant.setShopDesc(dto.getShopDesc());
        }
        merchant.setUpdateTime(LocalDateTime.now());
        merchantMapper.updateById(merchant);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeShopStatus(Long merchantId, Integer status) {
        UserContextHolder.requireAdmin();
        // 只允许三种状态流转，堵住任意值写入
        if (status == null || status < 1 || status > 3) {
            throw new BusinessException("状态参数不正确");
        }
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BusinessException("店铺不存在");
        }
        if (merchantMapper.updateShopStatus(merchantId, status) == 0) {
            throw new BusinessException("店铺状态已变更，请刷新重试");
        }

        // 冻结店铺时自动下架其全部商品：
        // 店铺被冻结还挂着商品，用户下单后无人发货，投诉率飙升
        if (status == 2) {
            List<Product> products = productMapper.selectByMerchantCondition(
                    merchantId, null, null, null, null);
            for (Product p : products) {
                p.setStatus(BizConst.PRODUCT_OFF_SHELF);
                productMapper.updateById(p);
                skuMapper.disableByProduct(p.getId());
            }
            log.warn("店铺 {} 已冻结，自动下架 {} 个商品", merchantId, products.size());
        }
    }

    @Override
    public List<Merchant> listShops(Integer status, String keyword) {
        UserContextHolder.requireAdmin();
        return merchantMapper.selectByCondition(status, keyword);
    }

    @Override
    public Long resolveMerchantId(Long targetMerchantId) {
        var user = UserContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        // 管理员可代操作任意店铺
        if (user.isAdmin()) {
            if (targetMerchantId == null) {
                throw new BusinessException("请指定要操作的店铺");
            }
            return targetMerchantId;
        }
        // 商家只能操作自己的店铺
        Long own = UserContextHolder.requireMerchantId();
        if (targetMerchantId != null && !targetMerchantId.equals(own)) {
            throw new BusinessException(ResultCode.DATA_NOT_BELONG_TO_YOU);
        }
        return own;
    }

    @Override
    public java.util.Map<String, Integer> getAdminTodoCount() {
        UserContextHolder.requireAdmin();
        java.util.Map<String, Integer> todo = new java.util.HashMap<>();
        Long applyCnt = applyMapper.countPending();
        Long productCnt = productMapper.countPendingAudit();
        todo.put("applyPending", applyCnt == null ? 0 : applyCnt.intValue());
        todo.put("productPending", productCnt == null ? 0 : productCnt.intValue());
        todo.put("total", (applyCnt == null ? 0 : applyCnt.intValue())
                + (productCnt == null ? 0 : productCnt.intValue()));
        return todo;
    }
}
