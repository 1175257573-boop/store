package com.ecommerce.service.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 店铺信息修改 DTO。
 *
 * <p><b>刻意不含资质类字段</b>（contactName/contactPhone/licenseNo/businessType）——
 * 这些是入驻时提交、平台审核过的资质，商家不能自己改，
 * 要改必须重新走审核流程。</p>
 */
@Data
public class MerchantUpdateDTO implements Serializable {

    @Size(max = 50, message = "店铺名称最长 50 字")
    private String shopName;

    @Size(max = 255, message = "Logo 地址过长")
    private String shopLogo;

    @Size(max = 500, message = "店铺简介最长 500 字")
    private String shopDesc;
}
