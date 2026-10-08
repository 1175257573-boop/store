package com.ecommerce.api.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.dao.entity.ImMessage;
import com.ecommerce.dao.mapper.ImSessionMapper;
import com.ecommerce.service.ImService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 买家 ↔ 商家 聊天接口。
 *
 * <p>需要登录才能访问；具体可见范围由 {@link ImService} 按角色强制过滤。
 * 未登录直接调会被 JWT 拦截器挡下（它不抛异常，在业务层判定）。
 */
@Tag(name = "10-买家咨询", description = "买家与店铺的一对一会话与消息")
@RestController
@RequestMapping("/api/im")
@RequiredArgsConstructor
public class ImController {

    private final ImService imService;

    @Operation(summary = "发起会话",
            description = "买家点击「联系商家」。幂等：同一买家与店铺只有一条会话，重复点击复用原会话")
    @PostMapping("/session")
    public Result<Map<String, Object>> openSession(@RequestBody Map<String, Long> body) {
        Long sessionId = imService.openSession(
                body.get("merchantId"), body.get("productId"));
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        return Result.success(data);
    }

    @Operation(summary = "会话列表",
            description = "按当前角色自动分流：买家看自己的，商家看本店的，管理员看全部")
    @GetMapping("/sessions")
    public Result<List<ImSessionMapper.ImSessionVO>> listSessions(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(imService.listSessions(pageNum, pageSize));
    }

    @Operation(summary = "未读消息数", description = "用于顶部红点徽标")
    @GetMapping("/unread")
    public Result<Map<String, Integer>> unread() {
        Map<String, Integer> data = new HashMap<>();
        data.put("unread", imService.unreadCount());
        return Result.success(data);
    }

    @Operation(summary = "拉取历史消息", description = "同时把对方发给我的消息标记为已读")
    @GetMapping("/message")
    public Result<List<ImMessage>> listMessages(
            @RequestParam Long sessionId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(imService.listMessages(sessionId, pageNum, pageSize));
    }

    @Operation(summary = "增量拉取消息",
            description = "轮询用：只返回 afterId 之后的新消息，不重复传输")
    @GetMapping("/message/poll")
    public Result<List<ImMessage>> pollMessages(
            @RequestParam Long sessionId,
            @RequestParam(required = false) Long afterId) {
        return Result.success(imService.pollMessages(sessionId, afterId));
    }

    @Operation(summary = "全部标记已读",
            description = "进入消息中心时调用；清空当前身份在所有会话里的未读")
    @PostMapping("/read-all")
    public Result<Integer> markAllRead() {
        return Result.success(imService.markAllRead());
    }

    @Operation(summary = "发送消息", description = "买家与商家均可发送；管理员不参与沟通")
    @PostMapping("/message")
    public Result<Map<String, Long>> sendMessage(@RequestBody Map<String, Object> body) {
        Long sessionId = Long.valueOf(String.valueOf(body.get("sessionId")));
        String content = String.valueOf(body.get("content"));
        Long msgId = imService.sendMessage(sessionId, content);
        Map<String, Long> data = new HashMap<>();
        data.put("messageId", msgId);
        return Result.success(data);
    }
}