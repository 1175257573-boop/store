package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.dao.entity.Address;
import com.ecommerce.service.dto.AddressDTO;

import java.util.List;

/**
 * 收货地址服务接口。
 */
public interface AddressService extends IService<Address> {

    /** 查询当前用户全部地址 */
    List<Address> listMyAddress();

    /** 查询默认地址，无默认则返回第一条 */
    Address getDefaultAddress();

    /** 新增地址 */
    Long addAddress(AddressDTO dto);

    /** 编辑地址 */
    void updateAddress(Long id, AddressDTO dto);

    /** 删除地址 */
    void deleteAddress(Long id);

    /** 设为默认地址 */
    void setDefault(Long id);
}