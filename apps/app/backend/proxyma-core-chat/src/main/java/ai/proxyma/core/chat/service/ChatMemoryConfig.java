package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.usage.ChatSpendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ChatMemoryConfig {

    @Value("${proxyma.conversations-dir:${user.home}/.proxyma/conversations}")
    private String conversationsDir;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    @Bean
    public SmartChatMemory smartChatMemory(JpaChatMemoryRepository repository,
                                           ConversationRepository conversationRepository,
                                           ChatMessageRepository messageRepository,
                                           LlmGateway llmGateway,
                                           SettingsService settingsService,
                                           ModelPricingService modelPricingService,
                                           ChatSpendService chatSpendService,
                                           ApplicationEventPublisher eventPublisher,
                                           ChatEventSink chatEventSink,
                                           ObjectMapper objectMapper) {
        return new SmartChatMemory(
                repository,
                conversationRepository,
                messageRepository,
                llmGateway,
                settingsService,
                modelPricingService,
                chatSpendService,
                eventPublisher,
                chatEventSink,
                conversationsDir,
                objectMapper,
                multiUser);
    }
}
