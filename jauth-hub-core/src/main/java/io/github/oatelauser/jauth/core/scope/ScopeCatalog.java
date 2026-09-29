package io.github.oatelauser.jauth.core.scope;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * scope 注册表接口：内置 OIDC 标准 scope 之外，宿主可注册自有 scope（目录 = 代码枚举 + i18n 描述，不建表，SPEC §3）。
 *
 * @author oatelauser
 */
public interface ScopeCatalog {

    /**
     * 注册（同名覆盖 upsert）：宿主重复注册同一 scope 视为更新描述/敏感位，幂等安全。
     *
     * @param definition 目录条目
     */
    void register(ScopeDefinition definition);

    /**
     * 按名查一条。
     *
     * @param name scope 名
     * @return 条目；目录未收录时空
     */
    Optional<ScopeDefinition> find(String name);

    /**
     * 全量快照（顺序稳定，供 consent 页等展示场景确定性渲染）。
     *
     * @return 目录全量条目列表
     */
    List<ScopeDefinition> all();

    /** 内置枚举：三枚 OIDC 标准 scope，内存实现启动即预注册。 */
    enum Builtin {
        OPENID,

        PROFILE,

        EMAIL;

        /**
         * 物化为目录条目。
         *
         * @return 对应 {@link ScopeDefinition}
         */
        public ScopeDefinition definition() {
            return ScopeDefinition.of(name().toLowerCase(Locale.ROOT), false);
        }
    }
}
