package com.personalprojections.locallife.copilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置（用于 LocalLife 内部 API 调用）。
 *
 * <p>连接超时和读取超时设置较短（5s / 10s），防止内部 API 调用拖慢 MCP 工具响应。
 * 工具默认超时配置在 application.yml: mcp.server.default-tool-timeout-ms
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);   // 5s 连接超时
        factory.setReadTimeout(10_000);     // 10s 读取超时
        return new RestTemplate(factory);
    }
}
