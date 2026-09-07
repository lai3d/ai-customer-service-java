package dev.merlionos.customerservice.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The pilot report: one tenant, one window, the numbers a customer is shown with their
 * definitions. Any signed-in member of staff, for their own tenant; platform staff name
 * one with {@code ?tenant=} (default when silent), since a sum over tenants is nobody's
 * report.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/reports")
class AdminReportController {

    static final int MAX_DAYS = 365;

    private final PilotReport report;

    AdminReportController(PilotReport report) {
        this.report = report;
    }

    @GetMapping("/pilot")
    PilotReport.Report pilot(@RequestParam(required = false) String tenant, @RequestParam(defaultValue = "30") int days,
                             Authentication authentication) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days is between 1 and " + MAX_DAYS);
        }
        Instant to = Instant.now();
        return report.over(StaffScope.of(authentication).oneTenant(tenant), to.minus(Duration.ofDays(days)), to);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
