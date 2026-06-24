package com.personalprojections.locallife.server.config;

import com.personalprojections.locallife.server.module.search.repository.PostSearchRepository;
import com.personalprojections.locallife.server.module.search.repository.ShopSearchRepository;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

@Configuration
@Profile("test")
public class TestExternalDependencyConfig {

    @Bean
    @Primary
    public RocketMQTemplate rocketMQTemplate() {
        return Mockito.mock(RocketMQTemplate.class);
    }

    @Bean
    @Primary
    public ElasticsearchOperations elasticsearchOperations() {
        return Mockito.mock(ElasticsearchOperations.class);
    }

    @Bean
    @Primary
    public ShopSearchRepository shopSearchRepository() {
        return Mockito.mock(ShopSearchRepository.class);
    }

    @Bean
    @Primary
    public PostSearchRepository postSearchRepository() {
        return Mockito.mock(PostSearchRepository.class);
    }
}
