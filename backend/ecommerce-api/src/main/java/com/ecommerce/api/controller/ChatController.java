package com.ecommerce.api.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.service.chat.ChatReply;
import com.ecommerce.service.chat.ChatScripts;
import com.ecommerce.service.chat.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 智能客服接口。
 *
 * <p>无需登录 —— 客服是公开能力，用户在浏览商品时随时能问。
 */
@Tag(name = "09-智能客服", description = "正则意图识别 + 知识库检索的智能客服")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "获取开场白",
            description = "客服打招呼并给出选项。带上 productName 时会锁定当前商品，后续追问都限定该商品")
    @GetMapping("/greeting")
    public Result<Map<String, Object>> greeting(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String productName) {
        // sessionId 由服务端生成并返回 —— 首次调用前端没有 id，
        // 不回传的话后续请求就拿不到上下文，商品锁定与连续追问都会失效。
        ChatService.ChatGreeting g = chatService.greeting(sessionId, productName);
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", g.sessionId());
        data.put("reply", g.reply());
        data.put("suggestions", new String[]{"1", "2", "3", "4", "5", "6"});
        return Result.success(data);
    }

    @Operation(summary = "发送消息",
            description = "意图识别 + 知识库检索；sessionId 必传（首次用 greeting 返回的）")
    @PostMapping("/send")
    public Result<ChatReply> send(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = body.get("sessionId");
        return Result.success(chatService.reply(sessionId, message));
    }
}