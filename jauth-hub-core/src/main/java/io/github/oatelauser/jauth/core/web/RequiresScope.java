package io.github.oatelauser.jauth.core.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 scope 校验标记（v1.2 C4，与 {@link RequiresSudo} 同域）：宿主业务方法标注即获得三合一接线
 * （三合一均由 starter 装配，业务侧零手写）：
 *
 * <ol>
 * <li><b>自动注册</b>：启动扫描同 JVM 全部 HandlerMethod，本注解声明的 scope 幂等进 {@code ScopeCatalog}
 * ——宿主自有接口的 scope 由此闭环进目录（SPEC §3：目录外 scope 在 PAT/安装勾选面被 A0505 拒）。</li>
 * <li><b>声明式校验</b>：{@code RequiresScopeInterceptor} 请求期校验当前主体 authorities 含本 scope
 * （原串与 {@code SCOPE_} 前缀双形态认键，403 走 Spring Security 标准语义）。</li>
 * <li><b>兜底文案</b>：desc 流入目录条目 fallbackDesc，consent 页 i18n key（{@code jauth.scope.<name>}）
 * 未命中时兜底展示（否则回退裸名）。</li>
 * </ol>
 *
 * <p><b>value 推荐冒号格式</b>（如 {@code orders:read}）：与 spring-plus 权限键形态一致，内省令牌的
 * scope 原串 authorities 可直接命中，宿主用 spring-plus 声明式鉴权时语义统一。
 *
 * <p><b>同 JVM 边界</b>：注解扫描只覆盖嵌入宿主/app 同进程的自有接口；独立部署的外部资源服务器
 * 仍走 {@code ScopeCatalog.register} 显式注册。
 *
 * <p>仅方法级（照 {@link RequiresSudo} 决议形态）：scope 面是逐端点的白名单决策，类级放大不在本批。
 * sensitive 只携带不消费（sudo 联动后续批次）。
 *
 * @author oatelauser
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresScope {

    /**
     * scope 名（协议里的 scope 字符串），推荐冒号格式（spring-plus 权限键对齐）。
     *
     * @return scope 名
     */
    String value();

    /**
     * 兜底文案：consent 页 i18n key 未命中时展示；空串表示无兜底（回退裸名）。
     *
     * @return 兜底文案
     */
    String desc() default "";

    /**
     * 是否敏感能力（sudo 触发语义，v1.2 本批只携带不消费）。
     *
     * @return 敏感位
     */
    boolean sensitive() default false;
}
