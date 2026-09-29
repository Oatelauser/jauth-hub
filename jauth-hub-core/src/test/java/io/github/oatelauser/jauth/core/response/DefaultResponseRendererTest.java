package io.github.oatelauser.jauth.core.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 默认渲染器字段形状：{code, message, data} 三段照抄 SimpleResponse 契约，成功码 00000（09 票）。
 *
 * @author oatelauser
 */
class DefaultResponseRendererTest {

    private final DefaultResponseRenderer renderer = new DefaultResponseRenderer();

    @Test
    @DisplayName("成功体：code=00000 + message + data 原样携带，恰好三段")
    void successHasFamilyContractShape() {
        Map<?, ?> body = (Map<?, ?>) renderer.renderSuccess("payload");

        assertEquals("00000", body.get("code"));
        assertEquals("success", body.get("message"));
        assertEquals("payload", body.get("data"));
        assertEquals(3, body.size());
    }

    @Test
    @DisplayName("成功体：无数据操作 data 置 null 且不炸（Map.of 不容 null 的坑）")
    void successCarriesNullDataForVoidOperations() {
        Map<?, ?> body = (Map<?, ?>) renderer.renderSuccess(null);

        assertNull(body.get("data"));
    }

    @Test
    @DisplayName("失败体：code/message 透传，data 为 null")
    void failCarriesCodeMessageAndNullData() {
        Map<?, ?> body =
                (Map<?, ?>) renderer.renderFail(JauthErrorCode.A0501.getCode(), JauthErrorCode.A0501.getMessage());

        assertEquals("A0501", body.get("code"));
        assertEquals("参数缺失", body.get("message"));
        assertNull(body.get("data"));
        assertEquals(3, body.size());
    }
}
