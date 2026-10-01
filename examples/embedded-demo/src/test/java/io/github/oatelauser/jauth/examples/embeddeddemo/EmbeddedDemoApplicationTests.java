package io.github.oatelauser.jauth.examples.embeddeddemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 内嵌示例最小验证：context 启动（starter 接管装配 + memory 存储 + 宿主自备 UserDetailsService）、公开接口
 * 200、受保护接口无 token 401。完整授权码闭环（浏览器登录→换码→持令牌调 /api/orders）留 B7 端到端，
 * 手动路径见 README 演练脚本。@RequiresScope 三合一的真实启动接线（orders:read 自动进目录）在下方钉住；
 * 令牌两态（命中 200/缺 403）由拦截器单测覆盖（MOCK 环境内省走真实 HTTP，无法造令牌）。
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class EmbeddedDemoApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScopeCatalog scopeCatalog;

    @Test
    @DisplayName("公开接口 /public/hello 无需令牌 200")
    void publicHelloOpen() throws Exception {
        this.mockMvc
                .perform(get("/public/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("hello from embedded-demo host (public endpoint, no token required)"));
    }

    @Test
    @DisplayName("受保护接口 /api/orders 无 token 401（rs-starter 内省接线就位）")
    void ordersWithoutTokenRejected() throws Exception {
        this.mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("@RequiresScope 三合一①：orders:read 启动自动进目录（desc 兜底文案随身）")
    void requiresScopeAnnotationRegistersOrdersScope() {
        ScopeDefinition definition = this.scopeCatalog.find("orders:read").orElseThrow();
        assertThat(definition.sensitive()).isFalse();
        assertThat(definition.fallbackDesc()).isEqualTo("读取订单");
        assertThat(definition.i18nKey()).isEqualTo("jauth.scope.orders:read");
    }
}
