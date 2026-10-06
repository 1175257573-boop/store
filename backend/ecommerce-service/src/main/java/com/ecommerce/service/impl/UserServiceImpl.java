package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.constant.RoleConst;
import com.ecommerce.common.context.LoginUser;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.Merchant;
import com.ecommerce.dao.entity.User;
import com.ecommerce.dao.mapper.MerchantMapper;
import com.ecommerce.dao.mapper.UserMapper;
import com.ecommerce.service.UserService;
import com.ecommerce.service.dto.LoginDTO;
import com.ecommerce.service.dto.ProfileUpdateDTO;
import com.ecommerce.service.dto.RegisterDTO;
import com.ecommerce.service.util.JwtUtil;
import com.ecommerce.service.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final MerchantMapper merchantMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterDTO dto) {
        // 唯一索引兜底，应用层先查一次给出友好提示（并发下仍由数据库约束保证最终一致）
        User exist = baseMapper.selectByUsername(dto.getUsername());
        if (exist != null) {
            throw new BusinessException(ResultCode.USERNAME_ALREADY_EXIST);
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        // BCrypt 自带盐值，同一密码每次加密结果不同，不可使用 MD5 等裸哈希
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setGender(0);
        user.setStatus(BizConst.USER_NORMAL);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        baseMapper.insert(user);
        log.info("用户注册成功: id={}, username={}", user.getId(), user.getUsername());
        return user.getId();
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        User user = baseMapper.selectByUsername(dto.getUsername());
        // 用户不存在与密码错误返回同一提示，避免被枚举出有效用户名
        if (user == null) {
            throw new BusinessException(ResultCode.USERNAME_OR_PASSWORD_ERROR);
        }
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.USERNAME_OR_PASSWORD_ERROR);
        }
        if (user.getStatus() == BizConst.USER_DISABLED) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        // 角色：管理员直接取表里存的；商家还要带上 merchantId，
        // 否则 Service 层无法判断「这条数据是不是本店的」
        Integer role = user.getRole() == null ? RoleConst.ROLE_USER : user.getRole();
        Long merchantId = null;
        if (role == RoleConst.ROLE_MERCHANT) {
            Merchant merchant = merchantMapper.selectByUserId(user.getId());
            // 店铺被冻结/注销时 merchantId 仍要带，但商家端接口会另行校验店铺状态
            if (merchant != null) {
                merchantId = merchant.getId();
            } else {
                // 角色是商家但查不到店铺：数据异常，降级为普通用户更安全
                log.warn("用户 {} 角色为商家但无店铺记录，按普通用户处理", user.getId());
                role = RoleConst.ROLE_USER;
            }
        }
        LoginUser loginUser = new LoginUser(
                user.getId(), user.getUsername(), user.getNickname(), role, merchantId);
        String token = jwtUtil.createToken(loginUser);

        // 店铺名：前端据此决定是否展示「进入商家中心」入口
        String shopName = null;
        if (merchantId != null) {
            Merchant m = merchantMapper.selectById(merchantId);
            shopName = m == null ? null : m.getShopName();
        }

        return LoginVO.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(role)
                .roleText(RoleConst.roleText(role))
                .merchantId(merchantId)
                .shopName(shopName)
                .build();
    }

    @Override
    public User getCurrentUser() {
        Long userId = UserContextHolder.requireUserId();
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.ACCOUNT_NOT_EXIST);
        }
        // 密码不出现在任何返回体中
        user.setPassword(null);
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(ProfileUpdateDTO dto) {
        Long userId = UserContextHolder.requireUserId();
        // DTO 已在入参层收窄字段，这里只搬允许修改的项
        User upd = new User();
        upd.setId(userId);
        upd.setNickname(dto.getNickname());
        upd.setPhone(dto.getPhone());
        upd.setEmail(dto.getEmail());
        upd.setAvatar(dto.getAvatar());
        upd.setGender(dto.getGender());
        if (dto.getBirthday() != null && !dto.getBirthday().isBlank()) {
            upd.setBirthday(LocalDate.parse(dto.getBirthday()));
        }
        baseMapper.updateProfile(upd);
        // 昵称变了要同步 ThreadLocal，否则后续请求仍用旧昵称
        LoginUser loginUser = UserContextHolder.get();
        if (loginUser != null) {
            loginUser.setNickname(dto.getNickname());
            UserContextHolder.set(loginUser);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(String oldPassword, String newPassword) {
        Long userId = UserContextHolder.requireUserId();
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.ACCOUNT_NOT_EXIST);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("原密码不正确");
        }
        User upd = new User();
        upd.setId(userId);
        upd.setPassword(passwordEncoder.encode(newPassword));
        baseMapper.updateById(upd);
        log.info("用户修改密码: id={}", userId);
    }
}