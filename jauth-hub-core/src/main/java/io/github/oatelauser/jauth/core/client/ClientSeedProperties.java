package io.github.oatelauser.jauth.core.client;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 客户端播种配置（{@code jauth-hub.clients[n].*}）。
 *
 * <p>语义（SPEC §2）：properties 是播种器不是第二真源——启动时 upsert 进活动仓库，存在即跳过， 运行期管理以数据库为准。clientSecret
 * 为<b>明文</b>，由 {@link ClientSeeder} 以注入的 {@code PasswordEncoder} 编码后落库；测试用占位值，不得放真实凭据。
 *
 * <p>集合访问器一律防御性拷贝：绑定期一次性写入、播种期只读，拷贝成本可忽略，同时不向调用方 暴露内部可变状态。
 *
 * @author oatelauser
 */
@ConfigurationProperties("jauth-hub")
public class ClientSeedProperties {

    private List<ClientSeed> clients = new ArrayList<>();

    public List<ClientSeed> getClients() {
        return new ArrayList<>(this.clients);
    }

    public void setClients(List<ClientSeed> clients) {
        this.clients = clients == null ? new ArrayList<>() : new ArrayList<>(clients);
    }

    /** 单个种子客户端的最小可用集。 */
    public static class ClientSeed {

        /** 业务 client_id（非主键）。 */
        private String clientId;

        /** 明文 secret，播种时编码落库；公开客户端留空。 */
        private String clientSecret;

        /** 展示名。 */
        private String clientName;

        /** 授权类型全名，如 authorization_code、refresh_token。 */
        private List<String> grantTypes = new ArrayList<>();

        /** 精确 redirect 白名单。 */
        private List<String> redirectUris = new ArrayList<>();

        /** 申请的 scope。 */
        private List<String> scopes = new ArrayList<>();

        /** 是否要求授权同意页（ClientSettings.requireAuthorizationConsent，默认 false 与框架一致）。 */
        private boolean requireAuthorizationConsent;

        public boolean isRequireAuthorizationConsent() {
            return this.requireAuthorizationConsent;
        }

        public void setRequireAuthorizationConsent(boolean requireAuthorizationConsent) {
            this.requireAuthorizationConsent = requireAuthorizationConsent;
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

        public String getClientName() {
            return this.clientName;
        }

        public void setClientName(String clientName) {
            this.clientName = clientName;
        }

        public List<String> getGrantTypes() {
            return new ArrayList<>(this.grantTypes);
        }

        public void setGrantTypes(List<String> grantTypes) {
            this.grantTypes = copyOf(grantTypes);
        }

        public List<String> getRedirectUris() {
            return new ArrayList<>(this.redirectUris);
        }

        public void setRedirectUris(List<String> redirectUris) {
            this.redirectUris = copyOf(redirectUris);
        }

        public List<String> getScopes() {
            return new ArrayList<>(this.scopes);
        }

        public void setScopes(List<String> scopes) {
            this.scopes = copyOf(scopes);
        }

        private static List<String> copyOf(List<String> source) {
            return source == null ? new ArrayList<>() : new ArrayList<>(source);
        }
    }
}
