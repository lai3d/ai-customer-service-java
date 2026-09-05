package dev.merlionos.customerservice.admin;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The admin's two pages, served from the classpath by hand rather than from {@code static/}
 * so that they have exactly the URLs the security configuration names and no others. Both
 * are single files in the style of the demo page: no build chain, {@code fetch} against
 * {@code /admin/api/**}.
 */
@Controller
class AdminPageController {

    private static final Resource INDEX = new ClassPathResource("admin/index.html");
    private static final Resource LOGIN = new ClassPathResource("admin/login.html");

    @GetMapping({AdminSecurityConfiguration.ADMIN_PATH, AdminSecurityConfiguration.ADMIN_PATH + "/"})
    ResponseEntity<Resource> index() {
        return page(INDEX);
    }

    @GetMapping(AdminSecurityConfiguration.LOGIN_PATH)
    ResponseEntity<Resource> login() {
        return page(LOGIN);
    }

    private static ResponseEntity<Resource> page(Resource page) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).body(page);
    }
}
