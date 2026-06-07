package com.personalprojections.locallife.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LocalLife Server 启动类。
 *
 * <h2>注解说明</h2>
 * <ul>
 *   <li>{@code @SpringBootApplication}：组合注解，等价于：
 *       <ul>
 *         <li>{@code @SpringBootConfiguration}  - 标记为配置类</li>
 *         <li>{@code @EnableAutoConfiguration}  - 开启 Spring Boot 自动配置
 *             （根据 classpath 中的依赖自动装配，如有 Redis 依赖就自动配置 RedisTemplate）</li>
 *         <li>{@code @ComponentScan}            - 扫描当前包及子包下的所有 Spring 组件
 *             （@Service、@Repository、@Component、@Controller 等）</li>
 *       </ul>
 *   </li>
 *   <li>{@code @MapperScan}：扫描指定包下所有标注了 {@code @Mapper} 的接口，
 *       为每个接口生成代理对象并注册为 Spring Bean。
 *       这样 Service 层可以直接 @Autowired 或构造器注入 UserMapper 等。
 *       包路径固定为 {@code **.mapper}，覆盖所有模块的 Mapper 接口。</li>
 * </ul>
 *
 * <h2>包结构约定</h2>
 * <pre>
 *   com.personalprojections.locallife.server
 *   ├── common/           公共组件（Result、异常、拦截器、上下文）
 *   ├── config/           Spring 配置类（Redis、MVC、MyBatis-Plus）
 *   ├── domain/
 *   │   ├── entity/       数据库实体（与表一一对应）
 *   │   └── mapper/       MyBatis-Plus Mapper 接口
 *   └── module/           业务模块（按领域拆分）
 *       ├── auth/         鉴权（发验证码、登录、退出）
 *       │   ├── controller/
 *       │   ├── service/
 *       │   └── dto/
 *       └── user/         用户（个人信息查询）
 *           ├── controller/
 *           ├── service/
 *           └── dto/
 * </pre>
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.personalprojections.locallife.server.domain.mapper")
public class LocalLifeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalLifeServerApplication.class, args);
    }
}
