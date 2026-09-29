package io.github.oatelauser.jauth.resourceserver;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * rs-starter 属性面（{@code jauth-hub.rs.*}，SPEC §2/§4：预接线 introspection + scope→权限映射）。
 *
 * <p>三项必填——{@code introspection-uri} 与机密客户端凭证 {@code client-id}/{@code client-secret}（06 票裁定：内省
 * 调用方须为机密客户端）。三项齐备装配才启动，缺任一项 starter 整体休眠（不产出任何 bean），宿主行为不受影响。
 *
 * <p>缓存默认 30s（06 票：撤销时延上限 30s，per-client 可配——此处的 per-client 指本资源服务器作为内省客户端
 * 可按部署调整）。正/负结果同缓存：负缓存防垃圾令牌打爆内省端点。
 *
 * <p>{@code authority-prefix} 默认空 = scope 原样作 authority；设 {@code "SCOPE_"} 时 {@code read:user} →
 * {@code SCOPE_read:user}（对齐 Spring Security 内建 NimbusOpaqueTokenIntrospector 的惯例）。
 *
 * @author oatelauser
 */
@ConfigurationProperties("jauth-hub.rs")
public class JauthResourceServerProperties {

    /** 内省端点地址（jauth-hub 的 /introspect，须为 https 或受信网络）。 */
    private String introspectionUri;

    /** 机密客户端 id（06 票：内省调用方须为机密客户端）。 */
    private String clientId;

    /** 机密客户端 secret（生产建议配合配置加密，勿明文入库）。 */
    private String clientSecret;

    /** scope→authority 前缀；空串 = scope 原样作 authority。 */
    private String authorityPrefix = "";

    private Cache cache = new Cache();

    public String getIntrospectionUri() {
        return this.introspectionUri;
    }

    public void setIntrospectionUri(String introspectionUri) {
        this.introspectionUri = introspectionUri;
    }

    public String getClientId() {
        return this.clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return this.clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getAuthorityPrefix() {
        return this.authorityPrefix;
    }

    public void setAuthorityPrefix(String authorityPrefix) {
        this.authorityPrefix = authorityPrefix;
    }

    /** 防御性拷贝进出（SpotBugs EI_EXPOSE_REP）：绑定期一次性写入、装配期只读，拷贝成本可忽略。 */
    public Cache getCache() {
        Cache copy = new Cache();
        copy.setTtl(this.cache.getTtl());
        copy.setNegativeTtl(this.cache.getNegativeTtl());
        copy.setMaxEntries(this.cache.getMaxEntries());
        return copy;
    }

    public void setCache(Cache cache) {
        if (cache == null) {
            this.cache = new Cache();
        } else {
            this.cache.setTtl(cache.getTtl());
            this.cache.setNegativeTtl(cache.getNegativeTtl());
            this.cache.setMaxEntries(cache.getMaxEntries());
        }
    }

    /**
     * 内省缓存参数（06 票：默认 30s，撤销时延上限；max-entries 防内存被无界令牌集撑爆）。
     */
    public static class Cache {

        /** 正结果 TTL（active 令牌的内省结果缓存时长 = 撤销可见时延上限）。 */
        private Duration ttl = Duration.ofSeconds(30);

        /** 负结果 TTL（active=false 也缓存，防垃圾令牌打爆内省端点）。 */
        private Duration negativeTtl = Duration.ofSeconds(30);

        /** 容量护栏：超限按 LRU 淘汰。 */
        private int maxEntries = 1000;

        public Duration getTtl() {
            return this.ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getNegativeTtl() {
            return this.negativeTtl;
        }

        public void setNegativeTtl(Duration negativeTtl) {
            this.negativeTtl = negativeTtl;
        }

        public int getMaxEntries() {
            return this.maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }
}
