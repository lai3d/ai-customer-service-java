package dev.merlionos.customerservice.orders.xboard;

import dev.merlionos.customerservice.orders.AccountLookupResult;
import dev.merlionos.customerservice.orders.ConnectorProperties;
import dev.merlionos.customerservice.orders.OrderConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A customer's account on the tenant's Xboard panel (a V2board-family subscription system),
 * read as that customer: the panel's user API with the customer's own Sanctum token in
 * {@code Authorization: Bearer}. Three calls -- {@code user/getSubscribe} (plan, expiry,
 * allowance, used), {@code user/info} (email, balance) and {@code user/order/fetch} (the
 * newest few) -- folded into {@link AccountLookupResult.Account}.
 *
 * <p>Units as the panel keeps them: traffic in bytes, amounts in cents, times as Unix
 * seconds. A refused token is {@code not_signed_in}; a panel that does not answer is
 * {@code unavailable}; neither is ever "you have no account".
 */
@Component
public class XboardAccountLookup {

    private static final Logger log = LoggerFactory.getLogger(XboardAccountLookup.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final double GB = 1024.0 * 1024 * 1024;
    private static final Map<Integer, String> ORDER_STATUS = Map.of(0, "pending payment", 1, "processing", 2, "cancelled",
            3, "completed", 4, "credited");

    private final RestClient.Builder builder;
    private final ConnectorProperties properties;

    public XboardAccountLookup(RestClient.Builder builder, ConnectorProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    public AccountLookupResult lookup(OrderConnector connector, String customerToken) {
        if (customerToken == null || customerToken.isBlank()) {
            return AccountLookupResult.notSignedIn();
        }
        try {
            RestClient client = client(connector, customerToken.strip());
            Map<String, Object> subscribe = data(client.get().uri("/api/v1/user/getSubscribe").retrieve().body(MAP));
            Map<String, Object> info = data(client.get().uri("/api/v1/user/info").retrieve().body(MAP));
            List<Map<String, Object>> orders = list(client.get().uri("/api/v1/user/order/fetch").retrieve().body(MAP));
            return AccountLookupResult.found(toAccount(subscribe, info, orders));
        }
        catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                return AccountLookupResult.notSignedIn();
            }
            log.warn("Xboard refused an account lookup for tenant {} ({}): {}", connector.tenantId(), connector.baseUrl(), e.getStatusCode());
            return AccountLookupResult.unavailable("The panel refused the request (" + e.getStatusCode().value()
                    + "); ask the customer to try again later, and do not guess at their subscription.");
        }
        catch (RuntimeException e) {
            log.warn("Xboard account lookup failed for tenant {} ({}): {}", connector.tenantId(), connector.baseUrl(), e.toString());
            return AccountLookupResult.unavailable("The panel could not be reached right now; ask the customer to try again in a "
                    + "few minutes, and do not guess at their subscription.");
        }
    }

    /** The panel's public configuration, for the admin's "test connection": no token needed. */
    public String panelName(OrderConnector connector) {
        Map<String, Object> body = client(connector, null).get().uri("/api/v1/guest/comm/config").retrieve().body(MAP);
        Map<String, Object> data = data(body);
        Object name = data.get("app_name");
        return name == null ? connector.baseUrl() : String.valueOf(name);
    }

    private RestClient client(OrderConnector connector, String token) {
        String base = properties.xboardBaseUrl() == null || properties.xboardBaseUrl().isBlank() ? connector.baseUrl() : properties.xboardBaseUrl();
        RestClient.Builder b = builder.clone().baseUrl(base)
                .defaultHeader("Accept", "application/json")
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(properties.timeoutOrDefault()).withReadTimeout(properties.timeoutOrDefault())));
        if (token != null) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return b.build();
    }

    // --- the panel's shapes into ours ------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        return body.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : body;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> body) {
        Object data = body == null ? null : body.get("data");
        return data instanceof List<?> l ? l.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList() : List.of();
    }

    @SuppressWarnings("unchecked")
    static AccountLookupResult.Account toAccount(Map<String, Object> subscribe, Map<String, Object> info, List<Map<String, Object>> orders) {
        Map<String, Object> plan = subscribe.get("plan") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        String planName = plan.get("name") == null ? null : String.valueOf(plan.get("name"));
        LocalDate expires = date(subscribe.get("expired_at"));
        boolean expired = expires != null && expires.isBefore(LocalDate.now(ZoneOffset.UTC));
        Double total = gb(subscribe.get("transfer_enable"));
        Double used = subscribe.get("u") == null && subscribe.get("d") == null ? null
                : gb(number(subscribe.get("u")) + number(subscribe.get("d")));
        Double remaining = total == null || used == null ? null : Math.max(0, round(total - used));
        Integer resetDay = subscribe.get("reset_day") == null ? null : (int) number(subscribe.get("reset_day"));
        Double balance = info.get("balance") == null ? null : round(number(info.get("balance")) / 100);
        List<AccountLookupResult.AccountOrder> recent = new ArrayList<>();
        for (Map<String, Object> o : orders.stream().limit(5).toList()) {
            Map<String, Object> orderPlan = o.get("plan") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            int status = (int) number(o.get("status"));
            recent.add(new AccountLookupResult.AccountOrder(text(o.get("trade_no")), text(orderPlan.get("name")),
                    o.get("total_amount") == null ? null : String.format("%.2f", number(o.get("total_amount")) / 100),
                    ORDER_STATUS.getOrDefault(status, "status " + status), date(o.get("created_at"))));
        }
        return new AccountLookupResult.Account(mask(text(info.get("email") != null ? info.get("email") : subscribe.get("email"))),
                planName, expires, expired, total, used == null ? null : round(used), remaining, resetDay, balance, recent);
    }

    static String mask(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        return (local.length() <= 2 ? local.charAt(0) + "*" : local.substring(0, 2) + "***") + email.substring(at);
    }

    private static Double gb(Object bytes) {
        return bytes == null ? null : round(number(bytes) / GB);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : value == null ? 0 : Double.parseDouble(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDate date(Object unixSeconds) {
        if (unixSeconds == null) {
            return null;
        }
        return Instant.ofEpochSecond((long) number(unixSeconds)).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
