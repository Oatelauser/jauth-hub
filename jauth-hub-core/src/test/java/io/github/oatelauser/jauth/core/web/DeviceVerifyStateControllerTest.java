package io.github.oatelauser.jauth.core.web;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 设备验证页 JSON 状态面（v1.4 B1）：educational 开关两态 + CSRF 字段（CsrfFilter 在场即出值）。
 * 认证面的 401 由 starter 真链测试钉死（core 无自动配置）。
 *
 * @author oatelauser
 */
class DeviceVerifyStateControllerTest {

    @Test
    @DisplayName("设备验证状态:educational=true + CSRF 字段在场")
    void educationalOnExposesCsrf() throws Exception {
        mockMvc(() -> true)
                .perform(get("/api/device/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));
    }

    @Test
    @DisplayName("教学开关关闭:educational=false,CSRF 字段仍在")
    void educationalOffStillExposesCsrf() throws Exception {
        mockMvc(() -> false)
                .perform(get("/api/device/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.educational").value(false))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())));
    }

    private static MockMvc mockMvc(EducationalFlag educational) {
        return MockMvcBuilders.standaloneSetup(
                        new DeviceVerifyStateController(educational, new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }
}
