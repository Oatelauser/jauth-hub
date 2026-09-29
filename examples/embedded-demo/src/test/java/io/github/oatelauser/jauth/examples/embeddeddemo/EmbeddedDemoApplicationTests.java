package io.github.oatelauser.jauth.examples.embeddeddemo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 内嵌示例最小验证：context 启动（starter 接管装配 + memory 存储 + 宿主自备 UserDetailsService）、公开接口
 * 200、受保护接口无 token 401。完整授权码闭环（浏览器登录→换码→持令牌调 /api/orders）留 B7 端到端，
 * 手动路径见 README 演练脚本。
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class EmbeddedDemoApplicationTests {

    @Autowired
    private MockMvc mockMvc;

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
}
