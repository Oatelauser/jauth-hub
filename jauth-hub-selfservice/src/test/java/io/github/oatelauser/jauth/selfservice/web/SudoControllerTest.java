package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * sudo 页路由单测（v1.5 B5b）：GET 无条件 302 到 /front/sudo（查询串原样，空查询不加尾缀 ?）；
 * {@link SudoController#safeReturnTo} 的消毒口径由 JSON 状态面（SudoStateController）消费，此处单点钉死。
 * 直调控制器不走 MockMvc——standalone 场景无视图解析器，重定向字符串断言已覆盖本路由职责。
 *
 * @author oatelauser
 */
class SudoControllerTest {

    @Test
    @DisplayName("GET 无条件 302 到 /front/sudo：returnTo 查询串原样保留；无查询串不加尾缀 ?")
    void redirectsToSpaRouteWithQueryPreserved() {
        SudoController controller = new SudoController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setQueryString("returnTo=/selfservice/pat");
        assertThat(controller.page(request)).isEqualTo("redirect:/front/sudo?returnTo=/selfservice/pat");

        assertThat(controller.page(new MockHttpServletRequest())).isEqualTo("redirect:/front/sudo");
    }

    @Test
    @DisplayName("safeReturnTo：本站路径透传，外站/协议相对/空回退看板")
    void returnToSanitized() {
        assertThat(SudoController.safeReturnTo("/profile")).isEqualTo("/profile");

        assertThat(SudoController.safeReturnTo("https://evil.example.com/phish"))
                .isEqualTo(SudoController.FALLBACK_RETURN_TO);
        assertThat(SudoController.safeReturnTo("//evil.example.com")).isEqualTo(SudoController.FALLBACK_RETURN_TO);
        assertThat(SudoController.safeReturnTo(null)).isEqualTo(SudoController.FALLBACK_RETURN_TO);
    }
}
