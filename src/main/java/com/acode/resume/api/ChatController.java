package com.acode.resume.api;

import com.acode.resume.chat.ResumeChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ResumeChatService service;

    public ChatController(ResumeChatService service) {
        this.service = service;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req) throws Exception {
        boolean debug = req.debug;
        ResumeChatService.Result r = service.answer(req.message, debug);
        return new ChatResponse(r.canAnswer, r.answer, r.citations, r.usedFields, r.debugHits);
    }
}
