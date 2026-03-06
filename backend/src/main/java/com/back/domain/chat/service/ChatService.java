package com.back.domain.chat.service;

import com.back.domain.chat.dto.ChatMessageDto;
import com.back.domain.chat.dto.request.ChatMessageRequestDto;
import com.back.domain.chat.dto.response.ChatMessageResponseDto;
import com.back.domain.chat.dto.response.ChatRoomResponseDto;
import com.back.domain.chat.entity.ChatMessage;
import com.back.domain.chat.entity.ChatRoom;
import com.back.domain.chat.repository.ChatMessageRepository;
import com.back.domain.chat.repository.ChatRoomRepository;
import com.back.domain.member.entity.Member;
import com.back.domain.member.exception.MemberErrorCode;
import com.back.domain.member.exception.MemberException;
import com.back.domain.member.repository.MemberRepository;
import com.back.domain.notification.entity.Notification;
import com.back.domain.notification.enums.NotificationType;
import com.back.domain.notification.repository.NotificationRepository;
import com.back.domain.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MemberRepository memberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;


    @Transactional
    public ChatRoomResponseDto createOrGetChatRoom(Long member1Id, Long member2Id) {
        Member member1 = memberRepository.findById(member1Id)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + member1Id));
        Member member2 = memberRepository.findById(member2Id)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + member2Id));

        // 기존 채팅방 찾기
        Optional<ChatRoom> existingRoom = chatRoomRepository.findByMembers(member1, member2);
        
        if (existingRoom.isPresent()) {
            log.info("Found existing chat room: {}", existingRoom.get().getId());
            return ChatRoomResponseDto.from(existingRoom.get());
        }

        // 새 채팅방 생성
        ChatRoom chatRoom = ChatRoom.builder()
                .firstMember(member1)
                .secondMember(member2)
                .build();
        
        ChatRoom savedRoom = chatRoomRepository.save(chatRoom);
        log.info("Created new chat room: {}", savedRoom.getId());

        // 상대방에게 채팅방 생성 알림 전송 (member1이 요청자라고 가정)
        try {
            notificationService.sendChatNotification(member2.getId(), member1.getName(),
                    "새 채팅방이 생성되었습니다");
            
            notifyChatRoomCreated(savedRoom);
            log.info("New chat room {} created, notified member {}", savedRoom.getId(), member2.getId());
        } catch (Exception e) {
            log.error("Failed to send notification for new chat room", e);
            // 알림 실패해도 채팅방 생성은 성공으로 처리
        }

        return ChatRoomResponseDto.from(savedRoom);
    }

    private void notifyChatRoomCreated(ChatRoom chatRoom) {
        // 두 사용자 모두에게 알림 전송
        messagingTemplate.convertAndSendToUser(
                String.valueOf(chatRoom.getFirstMember().getId()),
                "/queue/chat-rooms",
                ChatRoomResponseDto.from(chatRoom)
        );

        messagingTemplate.convertAndSendToUser(
                String.valueOf(chatRoom.getSecondMember().getId()),
                "/queue/chat-rooms",
                ChatRoomResponseDto.from(chatRoom)
        );
    }

    public void enterChatRoom(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found: " + roomId));

        // 상대방 찾기
        Member opponent;
        if (chatRoom.getFirstMember().getId().equals(userId)) {
            opponent = chatRoom.getSecondMember();
        } else {
            opponent = chatRoom.getFirstMember();
        }

        // 상대방에게 채팅방 입장 알림 보내기
        notificationService.sendMessageToUserEnterChatRoom(opponent);

        log.info("User {} entered room {}, notified opponent {}", userId, roomId, opponent.getId());
    }

    @Transactional
    public ChatMessage sendMessage(ChatMessageRequestDto request) {
        ChatRoom chatRoom = chatRoomRepository.findById(request.roomId())
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found: " + request.roomId()));

        Member sender = memberRepository.findById(request.senderId())
                .orElseThrow(() -> new IllegalArgumentException("Sender not found: " + request.senderId()));

        ChatMessage message = chatMessageRepository.save(request.toEntity(chatRoom, sender));
        ChatMessageResponseDto response = ChatMessageResponseDto.from(message);

        // Redis에 메시지 저장 (최근 100개 메시지, 24시간 유지)
        String redisKey = "chat:room:" + request.roomId() + ":messages";
        redisTemplate.opsForList().rightPush(redisKey, response); // rightPush로 변경 (시간순 저장)
        redisTemplate.opsForList().trim(redisKey, -100, -1); // 최근 100개만 유지 (오른쪽에서부터)
        redisTemplate.expire(redisKey, 24, TimeUnit.HOURS);

        // Redis Pub/Sub 발행 (트랜잭션 커밋 후 전송 권장)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisTemplate.convertAndSend("chat:room:" + request.roomId(), response);
            }
        });

        return message;
    }

    public List<ChatRoomResponseDto> getUserChatRooms(String memberEmail) {
        Member member = memberRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
        List<ChatRoom> chatRooms = chatRoomRepository.findByMember(member);
        return chatRooms.stream()
                .map(ChatRoomResponseDto::from)
                .toList();
    }

    public void viewRecentMessagesToUser(String memberEmail, Long roomId) {
        List<Object> recentMessages = getRecentMessagesFromRedis(roomId);

        if (recentMessages == null) return;

        Member member = memberRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        // Redis에서 가져온 메시지들을 ChatMessageResponse로 변환
        List<ChatMessageResponseDto> messageResponses = recentMessages.stream()
                .map(obj -> objectMapper.convertValue(obj, ChatMessageResponseDto.class))
                .toList();

        // 특정 사용자의 개인 큐로 최근 메시지 전송
        // 프론트엔드는 /user/queue/chat/{roomId}/messages 주소를 구독해야 함
        messagingTemplate.convertAndSendToUser(
                member.getEmail(), "/queue/user/" + roomId + "/messages", messageResponses);

        log.info("Sent {} recent messages to user {} for room {}", messageResponses.size(), member.getId(), roomId);
    }

    @Transactional
    public void deleteChatRoom(Long roomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found: " + roomId));

        // Redis에서 채팅방 관련 데이터 삭제 - 구독은 유지됨
        String redisKey = "chat:room:" + roomId + ":messages";
        redisTemplate.delete(redisKey);

        // DB에서 채팅방 삭제 (Cascade로 메시지도 자동 삭제)
        chatRoomRepository.delete(chatRoom);

        // 양쪽 사용자에게 채팅방 삭제 알림 전송 (순차적으로)
        // 멤버 알림
        notificationService.sendChatDeleteNotification(chatRoom.getFirstMember().getId(), "채팅방이 삭제되었습니다");
        notificationService.sendChatDeleteNotification(chatRoom.getSecondMember().getId(), "채팅방이 삭제되었습니다");

        log.info("Chat room {} deleted, cleaned Redis data and notified users", roomId);
    }

    private List<Object> getRecentMessagesFromRedis(Long roomId) {
        String redisKey = "chat:room:" + roomId + ":messages";
        return redisTemplate.opsForList().range(redisKey, 0, -1);
    }
}