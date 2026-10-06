package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.Address;
import com.ecommerce.dao.mapper.AddressMapper;
import com.ecommerce.service.AddressService;
import com.ecommerce.service.dto.AddressDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 收货地址服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements AddressService {

    @Override
    public List<Address> listMyAddress() {
        return baseMapper.selectByUserId(UserContextHolder.requireUserId());
    }

    @Override
    public Address getDefaultAddress() {
        Long userId = UserContextHolder.requireUserId();
        Address address = baseMapper.selectDefault(userId);
        if (address != null) {
            return address;
        }
        // 没有默认地址时兜底取最近一条，避免前端拿不到可用地址
        List<Address> list = baseMapper.selectByUserId(userId);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addAddress(AddressDTO dto) {
        Long userId = UserContextHolder.requireUserId();

        // 走 Mapper 里显式写的 countByUser，不 IService.count(lambdaQuery())：
        // 后者在本工程会抛 MybatisPlusException（详见 AddressMapper 注释）
        Long count = baseMapper.countByUser(userId);
        if (count != null && count >= BizConst.MAX_ADDRESS_COUNT) {
            throw new BusinessException(ResultCode.ADDRESS_LIMIT_EXCEED);
        }
        long current = count == null ? 0L : count;

        // isDefault 可能是 null（前端未传该字段），不能直接拆箱比较，
        // Integer 与 int 用 == 比较时 null 会抛 NPE
        boolean wantDefault = dto.getIsDefault() != null && dto.getIsDefault() == BizConst.YES;
        // 首个地址自动设为默认，否则用户会没有可用地址下单
        boolean needDefault = current == 0 || wantDefault;
        if (needDefault) {
            // 保证全局只有一个默认地址
            baseMapper.clearDefault(userId);
        }

        Address address = new Address();
        address.setUserId(userId);
        address.setReceiver(dto.getReceiver());
        address.setPhone(dto.getPhone());
        address.setProvince(dto.getProvince());
        address.setCity(dto.getCity());
        address.setDistrict(dto.getDistrict());
        address.setDetail(dto.getDetail());
        address.setIsDefault(needDefault ? BizConst.YES : BizConst.NO);
        address.setCreateTime(LocalDateTime.now());
        address.setUpdateTime(LocalDateTime.now());
        baseMapper.insert(address);
        return address.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAddress(Long id, AddressDTO dto) {
        Long userId = UserContextHolder.requireUserId();
        Address exist = baseMapper.selectOne(Wrappers.<Address>lambdaQuery()
                .eq(Address::getId, id)
                .eq(Address::getUserId, userId)
                .last("LIMIT 1"));
        if (exist == null) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_EXIST);
        }
        // Integer 与 int 用 == 比较时 null 会自动拆箱抛 NPE，必须先判空
        boolean wantDefault = dto.getIsDefault() != null && dto.getIsDefault() == BizConst.YES;
        if (wantDefault) {
            baseMapper.clearDefault(userId);
        }
        exist.setReceiver(dto.getReceiver());
        exist.setPhone(dto.getPhone());
        exist.setProvince(dto.getProvince());
        exist.setCity(dto.getCity());
        exist.setDistrict(dto.getDistrict());
        exist.setDetail(dto.getDetail());
        if (wantDefault) {
            exist.setIsDefault(BizConst.YES);
        }
        exist.setUpdateTime(LocalDateTime.now());
        baseMapper.updateById(exist);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAddress(Long id) {
        Long userId = UserContextHolder.requireUserId();
        Address exist = baseMapper.selectOne(Wrappers.<Address>lambdaQuery()
                .eq(Address::getId, id)
                .eq(Address::getUserId, userId)
                .last("LIMIT 1"));
        if (exist == null) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_EXIST);
        }
        baseMapper.deleteById(id);
        // 删掉的是默认地址时，把剩余最新一条顶为默认
        if (exist.getIsDefault() != null && exist.getIsDefault() == BizConst.YES) {
            List<Address> rest = baseMapper.selectByUserId(userId);
            if (!rest.isEmpty()) {
                baseMapper.setDefault(rest.get(0).getId(), userId);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long id) {
        Long userId = UserContextHolder.requireUserId();
        Address exist = baseMapper.selectOne(Wrappers.<Address>lambdaQuery()
                .eq(Address::getId, id)
                .eq(Address::getUserId, userId)
                .last("LIMIT 1"));
        if (exist == null) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_EXIST);
        }
        baseMapper.clearDefault(userId);
        baseMapper.setDefault(id, userId);
    }
}