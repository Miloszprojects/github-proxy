package com.miloszpodsiadly.githubproxy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class GithubProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(GithubProxyApplication.class, args);
    }

    @Bean
    RestClient githubRestClient(@Value("${github.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}
