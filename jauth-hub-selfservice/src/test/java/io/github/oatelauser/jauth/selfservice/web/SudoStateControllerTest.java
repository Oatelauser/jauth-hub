package io.github.oatelauser.jauth.selfservice.web;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * sudo 页 JSON 状态面（v1.4 B1）：sudoEnabled 两态（SudoGate 在场性 × passkey 开关）、returnTo 服务端
 * 消毒（外站/诡形回退看板）、CSRF 字段（CsrfFilter 在场即出值）。认证面归部署方 default 链（同 SSR 页，
 * starter 链不认领 /api/sudo），本测试不设认证面。
 *
 * @author oatelauser
 */
class SudoStateControllerTest {

    @Test
    @DisplayName("sudoEnabled=true:returnTo 本站路径透传,CSRF 字段在场")
    void enabledStatePassesThroughLocalReturnTo() throws Exception {
        mockMvc(true, () -> true)
                .perform(get("/api/sudo").queryParam("returnTo", "/selfservice/pat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.sudoEnabled").value(true))
                .andExpect(jsonPath("$.data.returnTo").value("/selfservice/pat"))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));
    }

    @Test
    @DisplayName("sudoEnabled=false:SudoGate 缺席(sudo 关)出 false 态,returnTo 仍消毒")
    void disabledStateRendersHintState() throws Exception {
        mockMvc(false, () -> true)
                .perform(get("/api/sudo").queryParam("returnTo", "/selfservice/pat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sudoEnabled").value(false))
                .andExpect(jsonPath("$.data.returnTo").value("/selfservice/pat"));
    }

    @Test
    @DisplayName("returnTo 消毒:外站 URL/协议相对/空一律回退看板")
    void returnToSanitized() throws Exception {
        MockMvc api = mockMvc(true, () -> true);
        api.perform(get("/api/sudo").queryParam("returnTo", "https://evil.example.com/phish"))
                .andExpect(jsonPath("$.data.returnTo").value(SudoController.FALLBACK_RETURN_TO));
        api.perform(get("/api/sudo").queryParam("returnTo", "//evil.example.com"))
                .andExpect(jsonPath("$.data.returnTo").value(SudoController.FALLBACK_RETURN_TO));
        api.perform(get("/api/sudo")).andExpect(jsonPath("$.data.returnTo").value(SudoController.FALLBACK_RETURN_TO));
    }

    private static MockMvc mockMvc(boolean sudoEnabled, PasskeyFlag passkey) {
        return MockMvcBuilders.standaloneSetup(new SudoStateController(
                        provider(gate(sudoEnabled)),
                        passkey,
                        (EducationalFlag) () -> true,
                        new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    private static @Nullable SudoGate gate(boolean enabled) {
        return enabled ? new SudoGate(new InMemoryUserRepository(), Duration.ofMinutes(15), Clock.systemUTC()) : null;
    }

    /** 按在场性返回固定 gate 的最小 ObjectProvider（控制器只读 getIfAvailable）。 */
    private static ObjectProvider<SudoGate> provider(@Nullable SudoGate gate) {
        return new ObjectProvider<>() {
            @Override
            public SudoGate getObject() {
                if (gate == null) {
                    throw new UnsupportedOperationException("no sudo gate in this context");
                }
                return gate;
            }

            @Override
            public SudoGate getIfAvailable() {
                return gate;
            }
        };
    }
}
