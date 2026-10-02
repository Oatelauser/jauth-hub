package io.github.oatelauser.jauth.starter;

import io.github.oatelauser.jauth.core.client.ClientSeedProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * jauth-hub 装配属性面（{@code jauth-hub.*}，SPEC §2 装配契约的宿主可调项）。
 *
 * <p>职责边界：这里只有<b>装配形态</b>（存储模式、链序、CORS、教学开关、issuer、播种清单）—— 令牌 TTL、密码策略等
 * per-client 策略一律走 client 注册的 TokenSettings（SPEC §6 策略表），不在此开属性面。 clients 播种直接透传 core
 * 的 {@link ClientSeedProperties.ClientSeed} 结构，语义同 core：启动 upsert，不是第二真源。
 *
 * <p>默认值整体面向"零依赖首跑"：memory 存储、教学层开、CORS 关、issuer 指向本机 9000 端口。 选 jdbc
 * 时宿主必须提供 {@code DataSource}（嵌入契约，SPEC §2 决议 4），缺失即启动失败（fail-fast， 不做静默回退）。
 *
 * @author oatelauser
 */
@ConfigurationProperties("jauth-hub")
public class JauthHubProperties {

    /** 存储模式词表：内存（默认）。 */
    public static final String STORAGE_MEMORY = "memory";

    /** 存储模式词表：JDBC（宿主供 DataSource）。 */
    public static final String STORAGE_JDBC = "jdbc";

    /** 协议链默认序位：低于常规宿主链（SPEC §2 宿主链共存四规则：@Order 默认 100 可配）。 */
    public static final int DEFAULT_FILTER_CHAIN_ORDER = 100;

    private String storage = STORAGE_MEMORY;

    private boolean educational = true;

    private Cors cors = new Cors();

    /** 限流与登录锁定（SPEC §6 默认值：5000/小时、连错 5 次锁 15 分钟）。 */
    private RateLimit rateLimit = new RateLimit();

    /** Passkey 强化层（SPEC §5 v1.2：默认关——协议链端点与凭据仓储仅在显式开启时装配）。 */
    private Passkey passkey = new Passkey();

    /** sudo 强验证层（SPEC §5 v1.2 C3：默认关；依赖 passkey，passkey 关而 sudo 开 → 启动 fail-fast）。 */
    private Sudo sudo = new Sudo();

    /** 信任面皮肤（SPEC §1 v1.4：SSR 永远默认，front 只是备选皮部署形态）。 */
    private TrustSkin trustSkin = TrustSkin.SSR;

    /** 播种清单（透传 core 的种子结构，两模式通用，幂等）。 */
    private List<ClientSeedProperties.ClientSeed> clients = new ArrayList<>();

    private int filterChainOrder = DEFAULT_FILTER_CHAIN_ORDER;

    private String issuer = "http://localhost:9000";

    public String getStorage() {
        return this.storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public boolean isEducational() {
        return this.educational;
    }

    public void setEducational(boolean educational) {
        this.educational = educational;
    }

    /** 防御性拷贝进出（SpotBugs EI_EXPOSE_REP）：绑定期一次性写入、装配期只读，拷贝成本可忽略。 */
    public Cors getCors() {
        Cors copy = new Cors();
        copy.setAllowedOrigins(this.cors.getAllowedOrigins());
        return copy;
    }

    public void setCors(Cors cors) {
        this.cors.setAllowedOrigins(cors == null ? List.of() : cors.getAllowedOrigins());
    }

    /** 防御性拷贝出入（与 Cors 同款理由）。 */
    public Passkey getPasskey() {
        Passkey copy = new Passkey();
        copy.setEnabled(this.passkey.isEnabled());
        copy.setRpId(this.passkey.getRpId());
        copy.setRpName(this.passkey.getRpName());
        copy.setAllowedOrigins(this.passkey.getAllowedOrigins());
        return copy;
    }

    public void setPasskey(Passkey passkey) {
        Passkey source = passkey == null ? new Passkey() : passkey;
        this.passkey.setEnabled(source.isEnabled());
        this.passkey.setRpId(source.getRpId());
        this.passkey.setRpName(source.getRpName());
        this.passkey.setAllowedOrigins(source.getAllowedOrigins());
    }

    /** 防御性拷贝出入（与 Cors 同款理由）。 */
    public Sudo getSudo() {
        Sudo copy = new Sudo();
        copy.setEnabled(this.sudo.isEnabled());
        copy.setTtlMinutes(this.sudo.getTtlMinutes());
        return copy;
    }

    public void setSudo(Sudo sudo) {
        Sudo source = sudo == null ? new Sudo() : sudo;
        this.sudo.setEnabled(source.isEnabled());
        this.sudo.setTtlMinutes(source.getTtlMinutes());
    }

    public TrustSkin getTrustSkin() {
        return this.trustSkin;
    }

    public void setTrustSkin(TrustSkin trustSkin) {
        this.trustSkin = trustSkin == null ? TrustSkin.SSR : trustSkin;
    }

    public List<ClientSeedProperties.ClientSeed> getClients() {
        return new ArrayList<>(this.clients);
    }

    public void setClients(List<ClientSeedProperties.ClientSeed> clients) {
        this.clients = clients == null ? new ArrayList<>() : new ArrayList<>(clients);
    }

    public int getFilterChainOrder() {
        return this.filterChainOrder;
    }

    public void setFilterChainOrder(int filterChainOrder) {
        this.filterChainOrder = filterChainOrder;
    }

    public String getIssuer() {
        return this.issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /** 防御性拷贝出入（与 Cors 同款理由）。 */
    public RateLimit getRateLimit() {
        RateLimit copy = new RateLimit();
        copy.setLimitPerHour(this.rateLimit.getLimitPerHour());
        copy.setLoginMaxFailures(this.rateLimit.getLoginMaxFailures());
        copy.setLoginLockMinutes(this.rateLimit.getLoginLockMinutes());
        return copy;
    }

    public void setRateLimit(RateLimit rateLimit) {
        RateLimit source = rateLimit == null ? new RateLimit() : rateLimit;
        this.rateLimit.setLimitPerHour(source.getLimitPerHour());
        this.rateLimit.setLoginMaxFailures(source.getLoginMaxFailures());
        this.rateLimit.setLoginLockMinutes(source.getLoginLockMinutes());
    }

    /**
     * 限流与登录锁定参数（SPEC §5 横切 + §6 默认策略表；限流内存计数器无表，票 07 工程项）。
     */
    public static class RateLimit {

        /** 每小时请求配额（按调用方主体合并桶，GitHub 真实模型）。 */
        private long limitPerHour = 5000;

        /** 登录连错阈值（达到即锁定）。 */
        private int loginMaxFailures = 5;

        /** 登录锁定时长（分钟）。 */
        private int loginLockMinutes = 15;

        public long getLimitPerHour() {
            return this.limitPerHour;
        }

        public void setLimitPerHour(long limitPerHour) {
            this.limitPerHour = limitPerHour;
        }

        public int getLoginMaxFailures() {
            return this.loginMaxFailures;
        }

        public void setLoginMaxFailures(int loginMaxFailures) {
            this.loginMaxFailures = loginMaxFailures;
        }

        public int getLoginLockMinutes() {
            return this.loginLockMinutes;
        }

        public void setLoginLockMinutes(int loginLockMinutes) {
            this.loginLockMinutes = loginLockMinutes;
        }
    }

    /**
     * 协议端点 CORS 配置（SPEC §4：默认空 = 不启用；仅协议端点，宿主自家接口不在其列）。
     */
    public static class Cors {

        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() {
            return new ArrayList<>(this.allowedOrigins);
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
        }
    }

    /**
     * Passkey（WebAuthn）强化层参数（SPEC §5 v1.2）。rpId/rpName/allowedOrigins 未显式配时由装配层从
     * {@code jauth-hub.issuer} 推导（host 即 rpId、scheme://host:port 即 origin），rpName 兜底 "jauth-hub"。
     */
    public static class Passkey {

        private boolean enabled = false;

        private String rpId;

        private String rpName;

        private List<String> allowedOrigins = new ArrayList<>();

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRpId() {
            return this.rpId;
        }

        public void setRpId(String rpId) {
            this.rpId = rpId;
        }

        public String getRpName() {
            return this.rpName;
        }

        public void setRpName(String rpName) {
            this.rpName = rpName;
        }

        public List<String> getAllowedOrigins() {
            return new ArrayList<>(this.allowedOrigins);
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
        }
    }

    /**
     * sudo 强验证层参数（SPEC §5 v1.2 C3）：敏感操作（@RequiresSudo 端点）要求最近一次 passkey 强认证
     * 仍在 TTL 内；依赖 passkey（强认证因子唯一来源），装配层校验 fail-fast。
     */
    public static class Sudo {

        private boolean enabled = false;

        /** 强认证新鲜窗口（分钟），窗口内敏感操作免重验（GitHub 同款语义）。 */
        private int ttlMinutes = 15;

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTtlMinutes() {
            return this.ttlMinutes;
        }

        public void setTtlMinutes(int ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }
    }

    /**
     * 信任面皮肤词表（v1.4 B4，属性 {@code jauth-hub.trust-skin}）：SSR 永远默认（issues/10 宪法），
     * front = 四页 GET 302 到 /front/&lt;路由&gt; 的 SPA 皮（jauth-hub-front 产物）。枚举绑定天然
     * fail-fast——非法取值启动即绑定失败，不静默回退。
     */
    public enum TrustSkin {

        /** SSR 皮（Thymeleaf，默认）。 */
        SSR,

        /** front 分离皮（/front/** 静态 + 302 路由）。 */
        FRONT
    }
}
