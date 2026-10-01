package io.github.oatelauser.jauth.core.scope;

import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * scope 目录条目：name（协议里的 scope 字符串）+ i18nKey（consent 页描述文案键，统一前缀 {@code jauth.scope.<name>}）+
 * sensitive（是否敏感能力，v1.2 sudo 再认证触发用，本批只携带）+ fallbackDesc（i18n key 未命中时的兜底文案，
 * v1.2 C4 {@code @RequiresScope} 的 desc 流入；空表示无兜底、consent 页回退裸名）。
 *
 * @author oatelauser
 */
public record ScopeDefinition(String name, String i18nKey, boolean sensitive, @Nullable String fallbackDesc) {

    /** i18n key 统一前缀：目录内 key 一律由此工厂落定，禁止手拼散落。 */
    private static final String I18N_KEY_PREFIX = "jauth.scope.";

    /**
     * 工厂方法（兼容形态，C4 前既有调用面）：由 name 推导 i18nKey，敏感位显式传入，无兜底文案。
     *
     * @param name scope 名（非空白）
     * @param sensitive 是否敏感能力（sudo 触发，v1.2 消费）
     * @return 目录条目
     */
    public static ScopeDefinition of(String name, boolean sensitive) {
        return of(name, sensitive, null);
    }

    /**
     * 工厂方法：fallbackDesc 为 consent 页 i18n 未命中时的兜底文案（可空——空则回退裸名，
     * {@code @RequiresScope} 的 desc 缺省即归一为该形态）。
     *
     * @param name scope 名（非空白）
     * @param sensitive 是否敏感能力
     * @param fallbackDesc 兜底文案（可空）
     * @return 目录条目
     */
    public static ScopeDefinition of(String name, boolean sensitive, @Nullable String fallbackDesc) {
        Assert.hasText(name, "name must not be blank");
        return new ScopeDefinition(name, I18N_KEY_PREFIX + name, sensitive, fallbackDesc);
    }
}
