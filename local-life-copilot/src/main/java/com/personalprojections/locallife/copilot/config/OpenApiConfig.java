package com.personalprojections.locallife.copilot.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI/Swagger 在线接口文档配置。
 *
 * <p>Swagger UI 访问地址：/swagger-ui.html。
 */
@Configuration
public class OpenApiConfig {

    private static final String MCP_IDENTITY_HEADERS = "mcp-identity-headers";

    @Bean
    public OpenAPI localLifeCopilotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LocalLife Copilot MCP API")
                        .version("0.1.0")
                        .description("LocalLife Copilot 的 Java MCP Server，提供 JSON-RPC 2.0 over HTTP 工具入口。")
                        .contact(new Contact().name("LocalLife")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local development")))
                .components(new Components()
                        .addSecuritySchemes(MCP_IDENTITY_HEADERS, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-User-Id")
                                .description("MCP 工具调用身份头。实际调用还需要 X-User-Role；merchant 角色需要 X-Merchant-Id。"))
                        .addParameters("X-User-Role", new HeaderParameter()
                                .name("X-User-Role")
                                .description("角色：merchant / cs / admin")
                                .required(true))
                        .addParameters("X-Merchant-Id", new HeaderParameter()
                                .name("X-Merchant-Id")
                                .description("merchant 角色必填，其他角色可不填")
                                .required(false)))
                .addSecurityItem(new SecurityRequirement().addList(MCP_IDENTITY_HEADERS));
    }
}
