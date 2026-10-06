package com.ecommerce.api.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.dao.entity.User;
import com.ecommerce.service.UserService;
import com.ecommerce.service.dto.LoginDTO;
import com.ecommerce.service.dto.ProfileUpdateDTO;
import com.ecommerce.service.dto.RegisterDTO;
import com.ecommerce.service.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 */
@Tag(name = "01-用户管理", description = "注册、登录、个人资料")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success("注册成功", userService.register(dto));
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success("登录成功", userService.login(dto));
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/profile")
    public Result<User> profile() {
        return Result.success(userService.getCurrentUser());
    }

    @Operation(summary = "更新个人资料")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody ProfileUpdateDTO dto) {
        userService.updateProfile(dto);
        return Result.success("资料已更新", null);
    }

    @Operation(summary = "修改密码")
    @PutMapping("/password")
    public Result<Void> changePassword(@RequestParam @NotBlank(message = "原密码不能为空") String oldPassword,
                                       @RequestParam @NotBlank(message = "新密码不能为空") String newPassword) {
        userService.changePassword(oldPassword, newPassword);
        return Result.success("密码已修改", null);
    }
}