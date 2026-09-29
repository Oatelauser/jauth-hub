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
}
