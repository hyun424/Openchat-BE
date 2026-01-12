package io.hyun424.openchat.hotchat;

import io.hyun424.openchat.hotchat.dto.HotChatResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hotchat")
public class HotChatController {

    private final HotChatService hotChatService;

    @GetMapping
    public List<HotChatResult> hotchat(
            @RequestParam(defaultValue = "5") int window,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return hotChatService.getHotChats(window, limit);
    }
}
