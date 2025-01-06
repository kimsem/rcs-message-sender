package com.rcs.external.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RCS 메시지 처리 API")
                        .description("RCS 메시지 처리 및 이벤트 허브 전송 API")
                        .version("1.0"));
    }
}