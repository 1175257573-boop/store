package com.ecommerce.service.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 店铺公开信息。
 *
 * <p><b>刻意不包含</b> userId、contactPhone、licenseNo：
 * 实体里的这些是商家隐私，用户端只需知道「哪家店开的、评分如何」。</p>
 */
@Data
public class ShopPublicVO implements Serializable {

    private Long id;

    private String shopName;

    private String shopLogo;

    private String shopDesc;

    private BigDecimal score;

    /** 在售商品数 */
    private Integer onShelfCount;
}
