package dev.merlionos.customerservice.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code k8s/observability} overlay carries the alert rules and the dashboards a second
 * time, and this is what keeps the second copy honest.
 *
 * <p>It has to be a copy. Kustomize refuses to reference any file outside the kustomization's
 * own directory -- {@code ../../observability/prometheus/rules/customer-service.yml} is a hard
 * error, not a shortcut, and this repository has already hit it once -- so the PrometheusRule
 * and the dashboard ConfigMaps cannot point at the files Compose mounts; they must contain
 * them. {@code scripts/render-k8s-observability.sh} writes the copies from the sources. Two
 * copies of a threshold drift the first time someone edits one and forgets the other, and
 * the symptom is an alert that fires on Compose and not on the cluster, months later. This
 * test turns that into a red build on the commit that did it: the committed PrometheusRule's
 * groups must equal the Compose rules file's groups, and each dashboard ConfigMap must carry
 * its JSON byte for byte. The fix is always the same, run the script and commit.
 *
 * <p>Reads files only; no Spring context, no Docker. The ServiceMonitor is written by hand,
 * so the last test checks it against the Services it claims to select rather than against a
 * source.
 */
class ObservabilityManifestsTest {

    private static final Path OVERLAY = Path.of("k8s/observability");
    private static final Path RULES = Path.of("observability/prometheus/rules/customer-service.yml");

    /** Rendered ConfigMap, and the dashboard it must carry. */
    private static final Map<String, Path> DASHBOARDS = Map.of(
            "dashboard-customer-service.yaml",
            Path.of("observability/grafana/dashboards/customer-service.json"),
            "dashboard-customer-service-roles.yaml",
            Path.of("observability/grafana/dashboards/customer-service-roles.json"));

    /** Every Service the ServiceMonitor is meant to cover: the base's one and the roles' three. */
    private static final List<Path> SERVICE_MANIFESTS = List.of(
            Path.of("k8s/base/service.yaml"),
            Path.of("k8s/roles/chat.yaml"),
            Path.of("k8s/roles/knowledge.yaml"),
            Path.of("k8s/roles/ticket.yaml"));

    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(Path file) throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> yamlDocuments(Path file) throws IOException {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : new Yaml().loadAll(Files.readString(file))) {
            if (document != null) {
                documents.add((Map<String, Object>) document);
            }
        }
        return documents;
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            current = ((Map<String, Object>) current).get(key);
            assertThat(current).as("missing %s", String.join(".", path)).isNotNull();
        }
        return (T) current;
    }

    @Test
    @DisplayName("the PrometheusRule carries exactly the groups of the Compose rules file")
    void prometheusRuleCarriesTheComposeRules() throws IOException {
        Map<String, Object> rule = yaml(OVERLAY.resolve("prometheus-rule.yaml"));
        assertThat(rule).containsEntry("apiVersion", "monitoring.coreos.com/v1")
                .containsEntry("kind", "PrometheusRule");

        List<Map<String, Object>> expected = at(yaml(RULES), "groups");
        List<Map<String, Object>> actual = at(rule, "spec", "groups");

        // Name the alert that differs before dumping both trees; a failure on one threshold is
        // otherwise a screenful of every rule.
        assertThat(actual).extracting(group -> group.get("name"))
                .as("group names")
                .containsExactlyElementsOf(expected.stream().map(group -> group.get("name")).toList());
        for (int g = 0; g < expected.size(); g++) {
            List<Map<String, Object>> expectedRules = at(expected.get(g), "rules");
            List<Map<String, Object>> actualRules = at(actual.get(g), "rules");
            assertThat(actualRules).extracting(r -> r.get("alert"))
                    .as("alerts in group %s", expected.get(g).get("name"))
                    .containsExactlyElementsOf(expectedRules.stream().map(r -> r.get("alert")).toList());
            for (int r = 0; r < expectedRules.size(); r++) {
                assertThat(actualRules.get(r))
                        .as("alert %s differs between %s and the PrometheusRule; run "
                                + "scripts/render-k8s-observability.sh", expectedRules.get(r).get("alert"), RULES)
                        .isEqualTo(expectedRules.get(r));
            }
        }
        assertThat(actual).as("spec.groups").isEqualTo(expected);
    }

    @Test
    @DisplayName("each dashboard ConfigMap carries its dashboard JSON, unchanged, under the sidecar label")
    void dashboardConfigMapsCarryTheComposeDashboards() throws IOException {
        for (Map.Entry<String, Path> entry : DASHBOARDS.entrySet()) {
            Path manifest = OVERLAY.resolve(entry.getKey());
            Path source = entry.getValue();
            Map<String, Object> configMap = yaml(manifest);

            assertThat(configMap).containsEntry("kind", "ConfigMap");
            Map<String, Object> labels = at(configMap, "metadata", "labels");
            assertThat(labels)
                    .as("%s: the kube-prometheus-stack Grafana sidecar only loads ConfigMaps with this label", manifest)
                    .containsEntry("grafana_dashboard", "1");

            Map<String, String> data = at(configMap, "data");
            String fileName = source.getFileName().toString();
            assertThat(data).as("%s: one dashboard per ConfigMap, named as the source file", manifest)
                    .containsOnlyKeys(fileName);

            String expected = Files.readString(source);
            JsonNode expectedTree = JSON.readTree(expected);
            JsonNode actualTree = JSON.readTree(data.get(fileName));
            assertThat(actualTree)
                    .as("%s differs from %s; run scripts/render-k8s-observability.sh", manifest, source)
                    .isEqualTo(expectedTree);
            // Byte for byte, not just structurally: a copy that is only equivalent is a copy
            // someone reformatted by hand, and the next render silently undoes their edit.
            assertThat(data.get(fileName))
                    .as("%s is not the exact bytes of %s; run scripts/render-k8s-observability.sh", manifest, source)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("the kustomization lists every file in the overlay, and only files that exist")
    void kustomizationListsEveryFile() throws IOException {
        List<String> resources = at(yaml(OVERLAY.resolve("kustomization.yaml")), "resources");

        Set<String> present;
        try (Stream<Path> files = Files.list(OVERLAY)) {
            present = files.map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals("kustomization.yaml"))
                    .collect(java.util.stream.Collectors.toSet());
        }
        // A rendered file the kustomization does not list is a dashboard nobody applies; a listed
        // file that does not exist is a build error the kind harness would catch, but later.
        assertThat(resources).as("resources listed in kustomization.yaml").containsExactlyInAnyOrderElementsOf(present);
        assertThat(resources).contains("servicemonitor.yaml", "prometheus-rule.yaml")
                .containsAll(DASHBOARDS.keySet());
    }

    @Test
    @DisplayName("the ServiceMonitor selects every Service of both layouts, on a port they name")
    void serviceMonitorSelectsBothLayouts() throws IOException {
        Map<String, Object> monitor = yaml(OVERLAY.resolve("servicemonitor.yaml"));
        assertThat(monitor).containsEntry("apiVersion", "monitoring.coreos.com/v1")
                .containsEntry("kind", "ServiceMonitor");

        Map<String, String> matchLabels = at(monitor, "spec", "selector", "matchLabels");
        List<String> namespaces = at(monitor, "spec", "namespaceSelector", "matchNames");
        List<Map<String, Object>> endpoints = at(monitor, "spec", "endpoints");
        assertThat(endpoints).hasSize(1);
        Map<String, Object> endpoint = endpoints.get(0);
        assertThat(endpoint).containsEntry("path", "/actuator/prometheus").containsEntry("interval", "15s");
        String port = (String) endpoint.get("port");

        // The dashboards' $role variable reads label_values(up, role); without this relabeling
        // every panel is empty on a cluster while it works on Compose.
        List<Map<String, Object>> relabelings = at(endpoint, "relabelings");
        assertThat(relabelings)
                .as("a relabeling that writes the component label into `role`")
                .anySatisfy(relabeling -> {
                    assertThat(relabeling).containsEntry("targetLabel", "role");
                    assertThat(relabeling.get("sourceLabels"))
                            .isEqualTo(List.of("__meta_kubernetes_service_label_app_kubernetes_io_component"));
                });

        int services = 0;
        for (Path manifest : SERVICE_MANIFESTS) {
            for (Map<String, Object> document : yamlDocuments(manifest)) {
                if (!"Service".equals(document.get("kind"))) {
                    continue;
                }
                services++;
                String name = manifest + " Service/" + at(document, "metadata", "name");
                String namespace = at(document, "metadata", "namespace");
                assertThat(namespaces).as("%s is in a namespace the monitor watches", name)
                        .contains(namespace);
                Map<String, String> labels = at(document, "metadata", "labels");
                assertThat(labels).as("%s carries the monitor's selector labels", name)
                        .containsAllEntriesOf(matchLabels);
                assertThat(labels).as("%s has the label the relabeling copies into `role`", name)
                        .containsKey("app.kubernetes.io/component");
                List<Map<String, Object>> ports = at(document, "spec", "ports");
                assertThat(ports).extracting(p -> p.get("name"))
                        .as("%s names the port the monitor scrapes", name)
                        .contains(port);
            }
        }
        assertThat(services).as("sanity: the base's one Service and the roles' three").isEqualTo(4);
    }
}
