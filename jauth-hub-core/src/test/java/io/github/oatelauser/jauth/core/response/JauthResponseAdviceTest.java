package io.github.oatelauser.jauth.core.response;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * advice 接 JauthException 委托 SPI 渲染：MockMvc 断言 JSON 形状；普通返回不被包装。
 *
 * @author oatelauser
 */
class JauthResponseAdviceTest {

    @RestController
    static class SubjectController {

        @GetMapping("/subject/boom")
        String boom() {
            throw new JauthException(JauthErrorCode.A0501);
        }

        @GetMapping("/subject/raw")
        String raw() {
            return "raw-body";
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SubjectController())
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .build();
    }

    @Test
    @DisplayName("JauthException 经 SPI 渲染为 {code,message,data} 失败体")
    void adviceRendersJauthExceptionThroughSpi() throws Exception {
        mockMvc.perform(get("/subject/boom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0501"))
                .andExpect(jsonPath("$.message").value("参数缺失"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("非 JauthException 的正常返回不被 advice 触碰")
    void rawReturnPassesThroughUntouched() throws Exception {
        mockMvc.perform(get("/subject/raw"))
                .andExpect(status().isOk())
                .andExpect(content().string("raw-body"));
    }
}
