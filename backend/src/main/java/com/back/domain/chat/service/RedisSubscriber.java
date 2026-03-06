package com.back.domain.chat.service;


import com.back.domain.chat.dto.response.ChatMessageResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSubscriber {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    // Redis에서 메시지 pub 시 호출됨
    public void sendMessage(String publishMessage) {
        try {
            // Redis에서 sub한 JSON 문자열을 DTO로 변환
            ChatMessageResponseDto response = objectMapper.readValue(publishMessage, ChatMessageResponseDto.class);

            // WebSocket subcriber 들에게 메시지 전달
            messagingTemplate.convertAndSend("/topic/chat/" + response.roomId(), response);
            log.info("Redis Pub -> WebSocket Send: Room {}", response.roomId());
        } catch (Exception e) {
            log.error("Exception in RedisSubscriber: {}", e.getMessage());
        }
    }
} 