package com.agentruntime.demo;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ChatModelConfig {
    public static final String MAIN_CHAT_MODEL = "mainChatModel";
    public static final String COMPACT_CHAT_MODEL = "compactChatModel";

    @Bean(MAIN_CHAT_MODEL)
    public ChatModel mainChatClient(OpenAiChatModel chatModel) {
        return chatModel;
    }

    @Bean(COMPACT_CHAT_MODEL)
    public ChatModel flashChatModel(OpenAiConnectionProperties connectionProperties,
                                    OpenAiChatProperties chatProperties,
                                    ObjectProvider<RestClient.Builder> restClientBuilderProvider,
                                    ObjectProvider<WebClient.Builder> webClientBuilderProvider,
                                    ResponseErrorHandler responseErrorHandler) {

        // clone：有共享 RestClient.Builder Bean 时也不能把 interceptor 挂到主模型那条链上
        RestClient.Builder flashRestClientBuilder = restClientBuilderProvider
                .getIfAvailable(RestClient::builder)
                .clone();

        OpenAiApi flashApi = OpenAiApi.builder()
                .baseUrl(connectionProperties.getBaseUrl())
                .apiKey(connectionProperties.getApiKey())
                .completionsPath(chatProperties.getCompletionsPath())
                .restClientBuilder(flashRestClientBuilder)
                .webClientBuilder(webClientBuilderProvider.getIfAvailable(WebClient::builder))
                .responseErrorHandler(responseErrorHandler)
                .build();

        OpenAiChatOptions flashOptions = OpenAiChatOptions.builder()
                .model("qwen3.6-flash")
                .temperature(0.0)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(flashApi)
                .defaultOptions(flashOptions)
                .build();
    }
}
