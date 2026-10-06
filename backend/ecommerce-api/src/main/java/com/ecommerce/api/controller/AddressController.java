package com.ecommerce.api.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.dao.entity.Address;
import com.ecommerce.service.AddressService;
import com.ecommerce.service.dto.AddressDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收货地址接口（需登录）。
 */
@Tag(name = "04-收货地址", description = "地址增删改查、默认地址")
@RestController
@RequestMapping("/api/address")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @Operation(summary = "查询我的地址列表", description = "默认地址排在最前")
    @GetMapping
    public Result<List<Address>> list() {
        return Result.success(addressService.listMyAddress());
    }

    @Operation(summary = "查询默认地址", description = "无默认地址时返回最近一条")
    @GetMapping("/default")
    public Result<Address> defaultAddress() {
        return Result.success(addressService.getDefaultAddress());
    }

    @Operation(summary = "新增收货地址")
    @PostMapping
    public Result<Long> add(@Valid @RequestBody AddressDTO dto) {
        return Result.success("地址已保存", addressService.addAddress(dto));
    }

    @Operation(summary = "编辑收货地址")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AddressDTO dto) {
        addressService.updateAddress(id, dto);
        return Result.success("地址已更新", null);
    }

    @Operation(summary = "删除收货地址", description = "删除默认地址后自动指定新默认")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.deleteAddress(id);
        return Result.success("地址已删除", null);
    }

    @Operation(summary = "设为默认地址")
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(id);
        return Result.success("已设为默认", null);
    }
}