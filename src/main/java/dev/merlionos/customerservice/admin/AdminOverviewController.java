package dev.merlionos.customerservice.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** The overview over a window; the last day unless told otherwise, never more than 90 days. */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/overview")
class AdminOverviewController {

    static final Duration MAX_WINDOW = Duration.ofDays(90);

    private final AdminOverview overview;

    AdminOverviewController(AdminOverview overview) {
        this.overview = overview;
    }

    @GetMapping
    AdminOverview.Overview overview(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        Instant[] window = AdminOverview.defaultWindow();
        Instant end = to == null || to.isBlank() ? window[1] : Instant.parse(to);
        Instant start = from == null || from.isBlank() ? end.minus(Duration.ofDays(1)) : Instant.parse(from);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("the window is at most " + MAX_WINDOW.toDays() + " days");
        }
        return overview.over(start, end);
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
    ResponseEntity<Map<String, String>> invalid(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
