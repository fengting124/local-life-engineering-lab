package com.personalprojections.locallife.server.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类，自定义序列化方式。
 *
 * <h2>为什么需要自定义序列化</h2>
 * <p>Spring Boot 默认的 {@link RedisTemplate} 使用 JDK 序列化（{@code JdkSerializationRedisSerializer}），
 * 存入 Redis 的数据是二进制格式，有以下问题：
 * <ol>
 *   <li>可读性差：用 redis-cli 查看时看不懂，排查问题困难</li>
 *   <li>空间浪费：JDK 序列化携带大量类元数据，体积远大于 JSON</li>
 *   <li>语言绑定：只有 Java 才能反序列化，与其他语言的服务不兼容</li>
 * </ol>
 *
 * <h2>本项目的序列化策略</h2>
 * <ul>
 *   <li>Key 使用 {@code StringRedisSerializer}：Key 就是字符串，直接存，无需额外序列化</li>
 *   <li>Value 使用 {@code Jackson2JsonRedisSerializer}：将 Java 对象序列化为 JSON 字符串存入 Redis</li>
 * </ul>
 *
 * <h2>两种 RedisTemplate 的用途</h2>
 * <ul>
 *   <li>{@link StringRedisTemplate}：Key 和 Value 都是纯字符串，
 *       适合存验证码（"123456"）、Token、计数器等简单字符串场景。
 *       Spring Boot 已自动注册，这里配置后注入即可使用。</li>
 *   <li>{@link RedisTemplate}{@code <String, Object>}：Value 是 Java 对象，
 *       Jackson 序列化为 JSON，适合存 LoginUserDTO 等复杂对象。</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * 配置通用 RedisTemplate，Key 为 String，Value 为 JSON 序列化的 Java 对象。
     *
     * <p>适用场景：存储复杂对象，如 LoginUserDTO（登录用户摘要）。
     *
     * <p>Jackson ObjectMapper 配置说明：
     * <ul>
     *   <li>{@code setVisibility}：让 Jackson 可以访问所有字段（包括 private），
     *       不需要为每个字段写 getter 才能序列化。</li>
     *   <li>{@code activateDefaultTyping}：在 JSON 中嵌入类型信息（如 "@class":"...UserDTO"），
     *       这样反序列化时 Jackson 知道要还原成哪个类，
     *       否则 Object 类型的 Value 反序列化后只能得到 LinkedHashMap。</li>
     * </ul>
     *
     * @param connectionFactory Spring Boot 自动配置的 Redis 连接工厂（Lettuce）
     * @return 配置好序列化方式的 RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 配置 Jackson ObjectMapper
        ObjectMapper objectMapper = new ObjectMapper();
        // 允许访问所有可见性的字段和方法，包括 private 字段
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 开启类型信息，NON_FINAL 表示对非 final 类都嵌入类型信息
        // 这样存入 Redis 的 JSON 类似：["com.xxx.LoginUserDTO",{"userId":10001,...}]
        // 反序列化时能正确还原为 LoginUserDTO，而不是 LinkedHashMap
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        // Value 序列化器：使用 Jackson 将对象转为 JSON
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);

        // Key 序列化器：直接存字符串，不做额外处理
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // 普通 Key-Value 的序列化方式
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);

        // Hash 结构的 Key-Value 序列化方式（用于 HSET/HGET 命令）
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);

        // 必须调用 afterPropertiesSet()，否则序列化配置不生效
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 配置 StringRedisTemplate。
     *
     * <p>Spring Boot 会自动注册一个 StringRedisTemplate，但为了与上面的配置保持一致，
     * 以及方便后续扩展（如添加连接工厂配置），这里显式声明。
     *
     * <p>适用场景：存储纯字符串，如：
     * <ul>
     *   <li>验证码：{@code login:code:{mobile} → "123456"}</li>
     *   <li>限流计数器：{@code login:sms:mobile:{mobile} → "3"}</li>
     *   <li>秒杀库存：{@code seckill:stock:{sessionId}:{couponId} → "100"}</li>
     * </ul>
     *
     * @param connectionFactory Redis 连接工厂
     * @return StringRedisTemplate
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
