package io.github.oatelauser.jauth.starter;

import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.springplus.web.response.SimpleResponse;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * spring-plus 桥（SPEC §2：starter 的唯一可选接入点——classpath 有 spring-plus-web 时自动切
 * SimpleResponse 版渲染器）。
 *
 * <p>条件装配理由：{@code @ConditionalOnClass(name=...)} 用字符串引用 SimpleResponse——本类必须在
 * spring-plus-web <b>不在场</b>的宿主里也能安全加载（编译面 optional），类名字符串不触发类初始化；
 * 渲染器内部类在条件通过后才加载，其方法体对 SimpleResponse 的引用不会外泄到宿主启动路径。
 *
 * <p>求值顺序：@AutoConfigureBefore({@link JauthHubAutoConfiguration})——桥先于主装配求值，SimpleResponse
 * 版渲染器先注册，主装配的 DefaultResponseRenderer（@ConditionalOnMissingBean）随之让位；宿主自定义
 * ResponseRenderer 恒最优先（用户配置先于一切自动配置）。字段名照抄 SimpleResponse（code/message/data），
 * 前端 JSON 与默认渲染器零差异（SPEC §1 错误码决议）。
 *
 * @author oatelauser
 */
@AutoConfiguration(before = JauthHubAutoConfiguration.class)
@ConditionalOnClass(name = "io.github.oatelauser.springplus.web.response.SimpleResponse")
public class JauthSpringPlusBridgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ResponseRenderer.class)
    ResponseRenderer simpleResponseResponseRenderer() {
        return new SimpleResponseRenderer();
    }

    /** SimpleResponse 版渲染器：成功走 ok(data)（成功码 00000 家族惯例），失败走 fail(code, message)。 */
    static final class SimpleResponseRenderer implements ResponseRenderer {

        @Override
        public Object renderSuccess(Object data) {
            return SimpleResponse.ok(data);
        }

        @Override
        public Object renderFail(String code, String message) {
            return SimpleResponse.fail(code, message);
        }
    }
}
