package com.back.domain.chat.dto.request;

import com.back.domain.chat.entity.ChatMessage;
import com.back.domain.chat.entity.ChatRoom;
import com.back.domain.member.entity.Member;
import lombok.Builder;

@Builder
public record ChatMessageRequestDto(
    Long roomId,
    Long senderId,
    String content
) {
    public ChatMessage toEntity(ChatRoom chatRoom, Member sender) {
        return ChatMessage.builder()
                .chatRoom(chatRoom)
                .sender(sender)
                .content(this.content)
                .build();
    }
}