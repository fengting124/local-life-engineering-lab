package com.personalprojections.locallife.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
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

    private static final String JWT_SECURITY_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI localLifeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LocalLife Server API")
                        .version("0.1.0")
                        .description("LocalLife 本地生活主业务后端接口，包含用户、门店、笔记、订单、支付、优惠券和秒杀能力。")
                        .contact(new Contact().name("LocalLife")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development"),
                        new Server().url("http://localhost").description("Nginx development gateway")))
                .components(new Components()
                        .addSecuritySchemes(JWT_SECURITY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录后把 token 填入 Swagger UI 的 Authorize 弹窗，不需要手写 Bearer 前缀。")))
                .addSecurityItem(new SecurityRequirement().addList(JWT_SECURITY_SCHEME));
    }
}
