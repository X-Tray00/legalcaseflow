package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.service.ChatService;
import bg.nbu.legalcaseflow.web.dto.request.*;
import bg.nbu.legalcaseflow.web.dto.response.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chats")
@PreAuthorize("hasAnyRole('CLIENT','LAWYER','ADMIN')")
@Tag(name = "Chats", description = "Private real-time conversations between clients, lawyers and administrators")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/contacts")
    public List<ChatContactResponse> contacts() {
        return chatService.contacts();
    }

    @GetMapping("/conversations")
    public List<ChatConversationResponse> conversations() {
        return chatService.conversations();
    }

    @PostMapping("/conversations")
    public ResponseEntity<ChatConversationResponse> createConversation(
            @Valid @RequestBody ChatConversationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatService.createConversation(request));
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessageResponse> messages(@PathVariable Long id,
                                              @RequestParam(required = false) Long beforeId,
                                              @RequestParam(defaultValue = "50") int limit) {
        return chatService.messages(id, beforeId, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<ChatMessageResponse> sendMessage(@PathVariable Long id,
                                                            @Valid @RequestBody ChatMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatService.sendMessage(id, request));
    }

    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        chatService.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/unread-count")
    public ChatUnreadCountResponse unreadCount() {
        return chatService.unreadCount();
    }
}
