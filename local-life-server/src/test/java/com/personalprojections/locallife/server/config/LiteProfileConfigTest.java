package com.personalprojections.locallife.server.config;

import com.personalprojections.locallife.server.module.mq.consumer.OrderCloseConsumer;
import com.personalprojections.locallife.server.module.mq.consumer.PaymentSuccessConsumer;
import com.personalprojections.locallife.server.module.mq.consumer.SeckillSuccessConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class LiteProfileConfigTest {

    @Test
    void elasticsearchConfigIsDisabledInLiteProfile() {
        assertThat(profileExpression(ElasticsearchConfig.class)).contains("!lite");
    }

    @Test
    void schedulingConfigIsDisabledInLiteProfile() {
        assertThat(profileExpression(SchedulingConfig.class)).contains("!lite");
    }

    @Test
    void rocketMqConsumersAreDisabledInLiteProfile() {
        assertThat(profileExpression(OrderCloseConsumer.class)).contains("!lite");
        assertThat(profileExpression(PaymentSuccessConsumer.class)).contains("!lite");
        assertThat(profileExpression(SeckillSuccessConsumer.class)).contains("!lite");
    }

    private static String profileExpression(Class<?> configClass) {
        Profile profile = AnnotationUtils.findAnnotation(configClass, Profile.class);
        assertThat(profile).isNotNull();
        return String.join(" ", profile.value());
    }
}
