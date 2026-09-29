package io.github.oatelauser.jauth.core.scope;

import org.springframework.util.Assert;

/**
 * scope 目录条目：name（协议里的 scope 字符串）+ i18nKey（consent 页描述文案键，统一前缀 {@code jauth.scope.<name>}）+
 * sensitive（是否敏感能力，v1.2 sudo 再认证触发用，本批只携带）。
 *
 * @author oatelauser
 */
public record ScopeDefinition(String name, String i18nKey, boolean sensitive) {

    /** i18n key 统一前缀：目录内 key 一律由此工厂落定，禁止手拼散落。 */
    private static final String I18N_KEY_PREFIX = "jauth.scope.";

    /**
     * 工厂方法：由 name 推导 i18nKey（{@code jauth.scope.<name>}），敏感位显式传入。
     *
     * @param name scope 名（非空白）
     * @param sensitive 是否敏感能力（sudo 触发，v1.2 消费）
     * @return 目录条目
     */
    public static ScopeDefinition of(String name, boolean sensitive) {
        Assert.hasText(name, "name must not be blank");
        return new ScopeDefinition(name, I18N_KEY_PREFIX + name, sensitive);
    }
}
