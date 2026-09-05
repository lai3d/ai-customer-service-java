package dev.merlionos.customerservice.tools;

import dev.merlionos.customerservice.orders.Order;
import dev.merlionos.customerservice.orders.OrderLookupResult;
import dev.merlionos.customerservice.orders.OrderStatus;
import dev.merlionos.customerservice.ticket.api.SupportTicket;
import dev.merlionos.customerservice.ticket.api.TicketResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model actually reads when a tool returns.
 *
 * <p>A tool result is not a data transfer object; it is prompt. The model never calls
 * {@code result.status()} -- it reads a string that something serialised, and every character
 * of that string is input to the next generation. The serialiser is therefore part of the
 * prompt, and until this file nothing in this repository looked at its output.
 *
 * <p>Every other test asserts on the Java object: {@code result.created()},
 * {@code result.ticket().ticketNumber()}. Those pass whatever the wire form turns out to be.
 * The .NET implementation of this system found the consequence the expensive way -- its
 * serialiser wrote an enum as {@code 1}, the model replied that it could not translate a
 * coded status, and every one of its tests was green, because each read the JSON back through
 * the same serialiser that had written it. **A round trip cannot see this. Only a literal
 * string can.**
 *
 * <p>Two things this file is careful about. It asserts on the literal string, and it
 * serialises through the converter the {@code @Tool} annotations actually name -- with
 * {@link #theToolsUseThisConverter()} to prove they name it. A converter that is written,
 * tested and never wired would pass every other test in this file while the model kept
 * reading arrays.
 *
 * <p>What was under test here was never only enums. Those were already fine: Jackson writes
 * enum names by default. The two {@code LocalDate}s inside {@code Order} were not, and the
 * order lookup is the most-called tool in this service.
 */
class ToolResultIsPromptTest {

    // The converter the @Tool annotations actually name. Using the default here instead
    // would test Spring AI rather than this service.
    private final ReadableToolResultConverter converter = new ReadableToolResultConverter();

    private String asTheModelSeesIt(Object result) {
        return converter.convert(result, result.getClass());
    }

    @Test
    @DisplayName("the tools name this converter, so the rest of this file is about what ships")
    void theToolsUseThisConverter() throws Exception {
        // Without this, every assertion below tests a class nothing calls. The bug being
        // fixed was invisible precisely because no test looked at the serialised form; a
        // converter that is never wired would restore that invisibility with a green suite
        // on top of it.
        for (var method : new java.lang.reflect.Method[] {
                OrderTools.class.getMethod("lookupOrderStatus", String.class,
                        org.springframework.ai.chat.model.ToolContext.class),
                SupportTicketTools.class.getMethod("createSupportTicket", String.class,
                        String.class, String.class,
                        org.springframework.ai.chat.model.ToolContext.class) }) {
            var tool = method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
            assertThat(tool).as("%s is not a @Tool any more", method.getName()).isNotNull();
            assertThat(tool.resultConverter())
                    .as("%s does not use ReadableToolResultConverter, so what the model reads "
                        + "is not what this test measures", method.getName())
                    .isEqualTo(ReadableToolResultConverter.class);
        }
    }

    // -- enums ------------------------------------------------------------------------

    @Test
    @DisplayName("an order status reaches the model as its name, not as an ordinal")
    void orderStatusIsAWord() {
        String json = asTheModelSeesIt(OrderLookupResult.found(order()));

        assertThat(json)
                .as("the model reads this string; a status it has to decode is a status it "
                    + "will tell the customer it cannot read")
                .contains("IN_TRANSIT")
                .doesNotContain("\"status\":2")
                .doesNotContain("\"status\":\"2\"");
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("every order status survives as a word")
    void everyOrderStatusIsAWord(OrderStatus status) {
        assertThat(asTheModelSeesIt(OrderLookupResult.found(order(status))))
                .as("%s reached the model as something other than its name", status)
                .contains(status.name());
    }

    @ParameterizedTest
    @EnumSource(TicketResult.Status.class)
    @DisplayName("every ticket status survives as a word")
    void everyTicketStatusIsAWord(TicketResult.Status status) {
        // Through the record the tool actually returns, not the bare enum: the field's
        // declared type is what decides how Jackson writes it.
        TicketResult result = switch (status) {
            case CREATED -> TicketResult.created(ticket());
            case EXISTING -> TicketResult.existing(ticket());
            case REFUSED -> TicketResult.refused("there are already three open tickets");
            case UNAVAILABLE -> TicketResult.unavailable();
        };

        assertThat(asTheModelSeesIt(result))
                .as("%s reached the model as something other than its name", status)
                .contains(status.name());
    }

    // -- dates ------------------------------------------------------------------------

    @Test
    @DisplayName("a delivery date reaches the model as a date, not as an array of integers")
    void datesAreDatesNotArrays() {
        String json = asTheModelSeesIt(OrderLookupResult.found(order()));

        assertThat(json)
                .as("a customer asking when a parcel arrives would be answered from %s", json)
                .contains("2026-09-03")
                .doesNotContain("[2026,9,3]")
                .doesNotContain("[2026,9,3]".replace(",", ", "));
    }

    // -- every leaf, not the ones I thought of ----------------------------------------

    @ParameterizedTest
    @MethodSource("everythingATheToolCanReturn")
    @DisplayName("every leaf a tool writes is readable as text")
    void everyLeafIsReadable(String what, Object result) throws Exception {
        // The assertions above name the fields I happened to think of, and that is exactly
        // how the Instant nearly slipped past: the first version of this file checked the
        // ticket result for its status and nothing else. This walks the whole tree instead,
        // so a field added later that serialises as an ordinal or an array fails here without
        // anyone remembering to assert on it.
        //
        // Taken from the .NET implementation, which arrived at the same guard from the other
        // side of the same bug.
        JsonNode root = new ObjectMapper().readTree(asTheModelSeesIt(result));
        walk(what, "", root);
    }

    private static void walk(String what, String path, JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(e -> walk(what, path + "/" + e.getKey(), e.getValue()));
            return;
        }
        if (node.isArray()) {
            // No leaf here is legitimately a list. `[2026,9,3]` is what a LocalDate looks
            // like without a time module, and it is indistinguishable from data.
            throw new AssertionError(what + path + " reaches the model as an array: " + node);
        }
        assertThat(node.isTextual() || node.isBoolean() || node.isNull())
                .as("%s%s reaches the model as %s (%s) -- the model reads text, and a value it "
                    + "has to decode is a value it may decode wrongly and tell a customer",
                    what, path, node.getNodeType(), node)
                .isTrue();

        String field = path.substring(path.lastIndexOf('/') + 1);
        if (node.isTextual() && field.matches(".*(On|At|Delivery)$")) {
            assertThat(node.asText())
                    .as("%s%s is a date field and is not ISO-8601", what, path)
                    .matches("\\d{4}-\\d{2}-\\d{2}([T ].*)?");
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> everythingATheToolCanReturn() {
        ToolResultIsPromptTest t = new ToolResultIsPromptTest();
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("order-found",
                        OrderLookupResult.found(t.order())),
                org.junit.jupiter.params.provider.Arguments.of("order-missing",
                        OrderLookupResult.notFound("No order ORD-99999 was found.")),
                org.junit.jupiter.params.provider.Arguments.of("ticket-created",
                        TicketResult.created(t.ticket())),
                org.junit.jupiter.params.provider.Arguments.of("ticket-existing",
                        TicketResult.existing(t.ticket())),
                org.junit.jupiter.params.provider.Arguments.of("ticket-refused",
                        TicketResult.refused("there are already three open tickets")),
                org.junit.jupiter.params.provider.Arguments.of("ticket-unavailable",
                        TicketResult.unavailable()));
    }

    // -- the sentences a customer may see verbatim -------------------------------------

    @Test
    @DisplayName("a ticket's creation time is readable too, not an epoch or an array")
    void instantsAreReadable() {
        // Added after the LocalDate failure: the first version of this file checked the
        // ticket result only for its status name and would have passed with an unreadable
        // timestamp beside it. Assert every temporal field the model is handed, not the one
        // that happened to fail.
        String json = asTheModelSeesIt(TicketResult.created(ticket()));

        assertThat(json)
                .as("the model is handed %s", json)
                .contains("2026-09-05")
                .doesNotContain("1788")          // epoch seconds for that instant
                .doesNotContain("[2026,9,5]");
    }

    @Test
    @DisplayName("a refusal explains itself in words the model can pass on")
    void aRefusalIsReadable() {
        String json = asTheModelSeesIt(TicketResult.refused("there are already three open tickets"));

        assertThat(json).contains("REFUSED");
        assertThat(json).contains("there are already three open tickets");
    }

    @Test
    @DisplayName("a missing order says so in a sentence, not as a bare false")
    void aMissingOrderIsASentence() {
        String json = asTheModelSeesIt(OrderLookupResult.notFound("No order ORD-99999 was found."));

        // Tool failures are values here rather than exceptions, and the value has to read as
        // something the model can hand to a customer. `{"found":false}` alone leaves it
        // inventing the sentence.
        assertThat(json).contains("false");
        assertThat(json).contains("ORD-99999");
    }

    private Order order() {
        return order(OrderStatus.IN_TRANSIT);
    }

    private Order order(OrderStatus status) {
        return new Order("ORD-10042", status,
                LocalDate.parse("2026-08-27"), LocalDate.parse("2026-09-03"),
                "SingPost", "SP884213906SG", "1 x noise-cancelling headphones");
    }

    private SupportTicket ticket() {
        return new SupportTicket("TKT-00001", "conversation-1", "returns",
                "the parcel arrived damaged", "ORD-10045",
                Instant.parse("2026-09-05T00:00:00Z"), false);
    }
}
