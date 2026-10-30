package com.example.utiltools.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI utilToolsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Util Tools REST API Server")
                        .description("High-performance developer utility API server built with Java 21 and Spring Boot. Features DateTime Converter, Crypto & Encoding Engine, and JSON Data Processor.")
                        .version("v1.0.0")
                        .contact(new Contact().name("API Team").email("api-team@example.com"))
                        .license(new License().name("Apache 2.0").url("https://spring.io")));
    }
}
