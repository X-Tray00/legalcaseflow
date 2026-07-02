package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.ChatConversationRepository;
import bg.nbu.legalcaseflow.repository.ChatMessageRepository;
import bg.nbu.legalcaseflow.repository.UserRepository;
import bg.nbu.legalcaseflow.web.dto.request.*;
import bg.nbu.legalcaseflow.web.dto.response.*;
import bg.nbu.legalcaseflow.websocket.ChatEventPublisher;
import bg.nbu.legalcaseflow.websocket.ChatWebSocketEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class ChatService {

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final ChatEventPublisher eventPublisher;
    private final AuditService auditService;

    public ChatService(ChatConversationRepository conversationRepository,
                       ChatMessageRepository messageRepository,
                       UserRepository userRepository,
                       CurrentUserService currentUserService,
                       ChatEventPublisher eventPublisher,
                       AuditService auditService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ChatContactResponse> contacts() {
        User actor = requireChatUser();
        return allowedCounterpartRoles(actor).stream()
                .flatMap(role -> userRepository.findByRoleOrderByUsernameAsc(role).stream())
                .filter(User::isActive)
                .filter(this::hasChatIdentity)
                .filter(user -> canStartConversation(actor, user))
                .sorted(Comparator.comparing(this::displayName, String.CASE_INSENSITIVE_ORDER))
                .map(user -> new ChatContactResponse(user.getId(), displayName(user), user.getRole().name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatConversationResponse> conversations() {
        User actor = requireChatUser();
        return visibleConversations(actor).stream()
                .map(conversation -> toConversationResponse(conversation, actor))
                .toList();
    }

    public ChatConversationResponse createConversation(ChatConversationRequest request) {
        User actor = requireChatUser();
        User counterpart = userRepository.findById(request.counterpartUserId())
                .orElseThrow(() -> NotFoundException.of("User", request.counterpartUserId()));
        requireCanStartConversation(actor, counterpart);

        // COURSEWORK: POST връща съществуващия разговор за същата двойка вместо да дублира историята.
        Optional<ChatConversation> existing = findConversation(actor, counterpart);
        ChatConversation conversation = existing.orElseGet(() -> {
                    Instant now = Instant.now();
                    ChatConversation created = new ChatConversation();
                    assignParticipants(created, actor, counterpart);
                    created.setCreatedAt(now);
                    created.setLastActivityAt(now);
                    return conversationRepository.save(created);
                });
        if (existing.isEmpty()) {
            auditService.record(AuditAction.CHAT_CONVERSATION_CREATED, "chat-conversations", conversation.getId(),
                    null, null, chatMetadata(conversation, actor, counterpart));
        }
        return toConversationResponse(conversation, actor);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messages(Long conversationId, Long beforeId, int requestedLimit) {
        User actor = requireChatUser();
        ChatConversation conversation = getConversationForParticipant(conversationId, actor);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageRequest page = PageRequest.of(0, limit);
        List<ChatMessage> messages = beforeId == null
                ? messageRepository.findByConversation_IdOrderByIdDesc(conversationId, page)
                : messageRepository.findByConversation_IdAndIdLessThanOrderByIdDesc(conversationId, beforeId, page);
        List<ChatMessageResponse> responses = new ArrayList<>(messages.stream()
                .map(message -> toMessageResponse(message, actor))
                .toList());
        Collections.reverse(responses);
        return responses;
    }

    public ChatMessageResponse sendMessage(Long conversationId, ChatMessageRequest request) {
        User sender = requireChatUser();
        ChatConversation conversation = getConversationForParticipant(conversationId, sender);
        String content = request.content().strip();
        if (content.isBlank() || content.length() > 2000) {
            throw new IllegalArgumentException("Message must contain between 1 and 2000 characters");
        }

        Instant now = Instant.now();
        ChatMessage message = new ChatMessage();
        message.setConversation(conversation);
        message.setSender(sender);
        message.setContent(content);
        message.setSentAt(now);
        messageRepository.save(message);
        conversation.setLastActivityAt(now);
        conversationRepository.save(conversation);

        User recipient = counterpartUser(conversation, sender);
        auditService.record(AuditAction.CHAT_MESSAGE_SENT, "chat-conversations", conversationId,
                null, null, chatMetadata(conversation, sender, recipient));
        eventPublisher.publishAfterCommit(sender.getUsername(),
                new ChatWebSocketEvent("MESSAGE_CREATED", conversationId, toMessageResponse(message, sender)));
        eventPublisher.publishAfterCommit(recipient.getUsername(),
                new ChatWebSocketEvent("MESSAGE_CREATED", conversationId, toMessageResponse(message, recipient)));
        eventPublisher.publishAfterCommit(recipient.getUsername(),
                new ChatWebSocketEvent("UNREAD_COUNT_CHANGED", null,
                        new ChatUnreadCountResponse(unreadCount(recipient))));
        return toMessageResponse(message, sender);
    }

    public void markRead(Long conversationId) {
        User reader = requireChatUser();
        ChatConversation conversation = getConversationForParticipant(conversationId, reader);
        Instant readAt = Instant.now();
        int updated = messageRepository.markReceivedMessagesRead(conversationId, reader.getId(), readAt);
        if (updated == 0) {
            return;
        }

        User counterpart = counterpartUser(conversation, reader);
        Map<String, Object> metadata = new LinkedHashMap<>(chatMetadata(conversation, reader, counterpart));
        metadata.put("reader", reader.getUsername());
        metadata.put("messagesMarkedRead", updated);
        auditService.record(AuditAction.CHAT_MESSAGES_READ, "chat-conversations", conversationId,
                null, null, metadata);
        ChatReadResponse payload = new ChatReadResponse(conversationId, reader.getId(), readAt);
        eventPublisher.publishAfterCommit(reader.getUsername(),
                new ChatWebSocketEvent("MESSAGES_READ", conversationId, payload));
        eventPublisher.publishAfterCommit(counterpart.getUsername(),
                new ChatWebSocketEvent("MESSAGES_READ", conversationId, payload));
        eventPublisher.publishAfterCommit(reader.getUsername(),
                new ChatWebSocketEvent("UNREAD_COUNT_CHANGED", null,
                        new ChatUnreadCountResponse(unreadCount(reader))));
    }

    @Transactional(readOnly = true)
    public ChatUnreadCountResponse unreadCount() {
        return new ChatUnreadCountResponse(unreadCount(requireChatUser()));
    }

    private long unreadCount(User user) {
        return visibleConversations(user).stream()
                .mapToLong(conversation -> messageRepository
                        .countByConversation_IdAndSender_IdNotAndReadAtIsNull(conversation.getId(), user.getId()))
                .sum();
    }

    private List<ChatConversation> visibleConversations(User user) {
        if (user.getRole() == Role.ADMIN) {
            return conversationRepository.findByAdmin_IdOrderByLastActivityAtDesc(user.getId());
        }
        if (user.getRole() == Role.CLIENT) {
            return conversationRepository.findByClient_IdOrderByLastActivityAtDesc(user.getClient().getId());
        }
        return conversationRepository.findByLawyer_IdOrderByLastActivityAtDesc(user.getLawyer().getId());
    }

    private ChatConversationResponse toConversationResponse(ChatConversation conversation, User viewer) {
        User counterpart = counterpartUser(conversation, viewer);
        ChatMessageResponse lastMessage = messageRepository.findTopByConversation_IdOrderByIdDesc(conversation.getId())
                .map(message -> toMessageResponse(message, viewer))
                .orElse(null);
        long unread = messageRepository.countByConversation_IdAndSender_IdNotAndReadAtIsNull(
                conversation.getId(), viewer.getId());
        return new ChatConversationResponse(
                conversation.getId(),
                counterpart.getId(),
                displayName(counterpart),
                counterpart.getRole().name(),
                conversation.getCreatedAt(),
                conversation.getLastActivityAt(),
                lastMessage,
                unread
        );
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message, User viewer) {
        return new ChatMessageResponse(
                message.getId(),
                message.getConversation().getId(),
                message.getSender().getId(),
                displayName(message.getSender()),
                message.getContent(),
                message.getSentAt(),
                message.getReadAt(),
                message.getSender().getId().equals(viewer.getId())
        );
    }

    private ChatConversation getConversationForParticipant(Long id, User user) {
        ChatConversation conversation = conversationRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("ChatConversation", id));
        if (!isParticipant(conversation, user)) {
            throw new AccessDeniedException("Cannot access this conversation");
        }
        return conversation;
    }

    private User counterpartUser(ChatConversation conversation, User viewer) {
        if (conversation.getAdmin() != null) {
            if (viewer.getRole() != Role.ADMIN) {
                return conversation.getAdmin();
            }
            if (conversation.getClient() != null) {
                return userRepository.findByClient_Id(conversation.getClient().getId())
                        .orElseThrow(() -> new IllegalStateException("Conversation client has no login account"));
            }
            return userRepository.findByLawyer_Id(conversation.getLawyer().getId())
                    .orElseThrow(() -> new IllegalStateException("Conversation lawyer has no login account"));
        }
        if (viewer.getRole() == Role.CLIENT) {
            return userRepository.findByLawyer_Id(conversation.getLawyer().getId())
                    .orElseThrow(() -> new IllegalStateException("Conversation lawyer has no login account"));
        }
        return userRepository.findByClient_Id(conversation.getClient().getId())
                .orElseThrow(() -> new IllegalStateException("Conversation client has no login account"));
    }

    private boolean isParticipant(ChatConversation conversation, User user) {
        if (user.getRole() == Role.ADMIN) {
            return conversation.getAdmin() != null && user.getId().equals(conversation.getAdmin().getId());
        }
        if (user.getRole() == Role.CLIENT) {
            return conversation.getClient() != null
                    && user.getClient().getId().equals(conversation.getClient().getId());
        }
        return user.getRole() == Role.LAWYER && conversation.getLawyer() != null
                && user.getLawyer().getId().equals(conversation.getLawyer().getId());
    }

    private User requireChatUser() {
        User user = currentUserService.currentUser();
        if (!hasChatIdentity(user)) {
            throw new AccessDeniedException("Chat is available only to active users with a valid profile");
        }
        return user;
    }

    private void requireCanStartConversation(User actor, User counterpart) {
        if (!counterpart.isActive() || !hasChatIdentity(counterpart) || !canStartConversation(actor, counterpart)) {
            throw new IllegalArgumentException("This user cannot start a conversation with the selected account");
        }
    }

    private boolean canStartConversation(User actor, User counterpart) {
        // COURSEWORK: матрицата за започване е отделна от правото за отговор. CLIENT не може
        // да започне с ADMIN, но участва и отговаря, ако ADMIN е създал разговора.
        return switch (actor.getRole()) {
            case ADMIN -> counterpart.getRole() == Role.CLIENT || counterpart.getRole() == Role.LAWYER;
            case LAWYER -> counterpart.getRole() == Role.CLIENT || counterpart.getRole() == Role.ADMIN;
            case CLIENT -> counterpart.getRole() == Role.LAWYER;
        };
    }

    private List<Role> allowedCounterpartRoles(User actor) {
        return switch (actor.getRole()) {
            case ADMIN -> List.of(Role.CLIENT, Role.LAWYER);
            case LAWYER -> List.of(Role.CLIENT, Role.ADMIN);
            case CLIENT -> List.of(Role.LAWYER);
        };
    }

    private boolean hasChatIdentity(User user) {
        return user.getRole() == Role.ADMIN
                || user.getRole() == Role.CLIENT && user.getClient() != null
                || user.getRole() == Role.LAWYER && user.getLawyer() != null;
    }

    private Optional<ChatConversation> findConversation(User actor, User counterpart) {
        User admin = userWithRole(actor, counterpart, Role.ADMIN);
        User clientUser = userWithRole(actor, counterpart, Role.CLIENT);
        User lawyerUser = userWithRole(actor, counterpart, Role.LAWYER);
        if (admin != null && clientUser != null) {
            return conversationRepository.findByAdmin_IdAndClient_Id(admin.getId(), clientUser.getClient().getId());
        }
        if (admin != null && lawyerUser != null) {
            return conversationRepository.findByAdmin_IdAndLawyer_Id(admin.getId(), lawyerUser.getLawyer().getId());
        }
        return conversationRepository.findByClient_IdAndLawyer_Id(
                clientUser.getClient().getId(), lawyerUser.getLawyer().getId());
    }

    private void assignParticipants(ChatConversation conversation, User actor, User counterpart) {
        User admin = userWithRole(actor, counterpart, Role.ADMIN);
        User clientUser = userWithRole(actor, counterpart, Role.CLIENT);
        User lawyerUser = userWithRole(actor, counterpart, Role.LAWYER);
        conversation.setAdmin(admin);
        conversation.setClient(clientUser == null ? null : clientUser.getClient());
        conversation.setLawyer(lawyerUser == null ? null : lawyerUser.getLawyer());
    }

    private User userWithRole(User first, User second, Role role) {
        if (first.getRole() == role) {
            return first;
        }
        return second.getRole() == role ? second : null;
    }

    private String displayName(User user) {
        if (user.getRole() == Role.CLIENT) {
            return user.getClient().getFullName();
        }
        if (user.getRole() == Role.LAWYER) {
            return user.getLawyer().getFullName();
        }
        return user.getUsername();
    }

    private Map<String, Object> chatMetadata(ChatConversation conversation, User actor, User counterpart) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (conversation.getClient() != null) {
            metadata.put("clientName", conversation.getClient().getFullName());
        }
        if (conversation.getLawyer() != null) {
            metadata.put("lawyerName", conversation.getLawyer().getFullName());
        }
        if (conversation.getAdmin() != null) {
            metadata.put("adminUsername", conversation.getAdmin().getUsername());
        }
        metadata.put("actor", actor.getUsername());
        metadata.put("counterpart", counterpart.getUsername());
        return metadata;
    }
}
