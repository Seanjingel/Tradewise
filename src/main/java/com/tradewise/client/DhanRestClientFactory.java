package com.tradewise.client;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Factory for creating configured RestClient instances.
 */
@Component
public class DhanRestClientFactory {

    private final DhanHttpClientFactory httpClientFactory;

    public DhanRestClientFactory(DhanHttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
    }

    public RestClient create() {
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClientFactory.create()))
                .build();
    }
}

