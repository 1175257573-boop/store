package com.ecommerce.service;

import com.ecommerce.dao.entity.Merchant;
import com.ecommerce.dao.entity.MerchantApply;
import com.ecommerce.service.dto.MerchantApplyDTO;
import com.ecommerce.service.dto.MerchantUpdateDTO;

import java.util.List;

/**
 * 商家服务接口。
 */
public interface MerchantService {

    /**
     * 提交入驻申请。
     * <p>前置校验：已是商家不能重复申请；有待审核申请时提示等待。</p>
     */
    Long apply(MerchantApplyDTO dto);

    /** 查我的入驻申请（含被拒绝原因） */
    MerchantApply getMyApply();

    /** 撤销我的申请（仅待审核状态可撤销） */
    void revokeApply(Long applyId);

    /**
     * 审核入驻申请（平台管理员）。
     *
     * @param pass   true=通过（自动建店并把用户角色升为商家）
     * @param remark 审核意见
     */
    void audit(Long applyId, boolean pass, String remark);

    /** 申请列表（平台管理员） */
    List<MerchantApply> listApplies(Integer status, String keyword);

    /**
     * 商家的店铺详情。
     * <p>商家看自己的，管理员可看任意。</p>
     */
    Merchant getShopInfo();

    /** 管理员看指定店铺 */
    Merchant getShopById(Long merchantId);

    /**
     * 公开的店铺信息（用户端商品页展示店铺名用）。
     * <p>走 DTO 而非直接返回实体：实体含 owner 的 userId、
     * 营业执照号等不该公开的字段。</p>
     */
    com.ecommerce.service.vo.ShopPublicVO getPublicShopInfo(Long merchantId);

    /** 更新店铺信息（只能改非资质类字段） */
    void updateShop(MerchantUpdateDTO dto);

    /**
     * 冻结 / 解冻店铺（平台管理员）。
     * <p>冻结后该店铺的商品自动下架、不能发货。</p>
     */
    void changeShopStatus(Long merchantId, Integer status);

    /** 店铺列表（平台管理员） */
    List<Merchant> listShops(Integer status, String keyword);

    /**
     * 拿当前操作者的商家 ID，管理员返回入参。
     * <p>用于「管理员可代操作任意店铺」的场景。</p>
     */
    Long resolveMerchantId(Long targetMerchantId);

    /**
     * 管理员待办数量（侧边栏红点用）。
     * <p>一次性返回入驻审核与商品审核两个待审数量，避免前端发两次请求。</p>
     */
    java.util.Map<String, Integer> getAdminTodoCount();
}
