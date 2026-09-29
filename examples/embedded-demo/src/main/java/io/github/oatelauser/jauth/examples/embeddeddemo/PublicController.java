package io.github.oatelauser.jauth.examples.embeddeddemo;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宿主公开业务接口：不需要任何令牌（default 链白名单放行）。
 *
 * @author oatelauser
 */
@RestController
public class PublicController {

    @GetMapping("/public/hello")
    public Map<String, String> hello() {
        return Map.of("message", "hello from embedded-demo host (public endpoint, no token required)");
    }
}
