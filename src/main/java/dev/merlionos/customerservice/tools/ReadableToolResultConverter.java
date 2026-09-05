package dev.merlionos.customerservice.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;

/**
 * Serialises tool results the way a reader would write them, because the reader is the model.
 *
 * <p>Spring AI's {@code DefaultToolCallResultConverter} serialises through a Jackson mapper
 * with no time module registered, so a {@code LocalDate} leaves as an array of integers. The
 * order lookup — the most-called tool here — was sending this:
 *
 * <pre>{@code
 * {"found":true,"order":{"orderNumber":"ORD-10042","status":"IN_TRANSIT",
 *  "placedOn":[2026,8,27],"estimatedDelivery":[2026,9,3], ...}}
 * }</pre>
 *
 * <p>A customer asking when their parcel arrives was being answered from {@code [2026,9,3]}.
 *
 * <p><strong>It worked.</strong> That is the uncomfortable part and the reason it survived: the
 * model reads the array, infers that a three-element integer array beginning 2026 is a date in
 * year-month-day order, and writes "3 September 2026". The demo screenshots are correct. The
 * system was relying on the model being generous with a format nobody chose, in a field where
 * being wrong means telling a customer the wrong delivery date — and `[2026,9,3]` and
 * `[3,9,2026]` are equally plausible readings of a convention that was never stated.
 *
 * <p>Found by applying a finding from the .NET implementation of this system, which hit the
 * same class of bug one type over: its serialiser wrote an enum as `1`, and its model — less
 * generous — replied that it could not translate a coded status. Enums are fine here; Jackson
 * writes names by default. Dates were not, and nothing was looking, because every test in this
 * repository asserted on the Java object rather than on the string the model is handed.
 *
 * <p>Registered per tool via {@code @Tool(resultConverter = ...)}. Not a global Jackson change:
 * this mapper exists to make one thing readable to one reader, and the rest of the application
 * has its own serialisation concerns that have nothing to do with prompting.
 */
public class ReadableToolResultConverter implements ToolCallResultConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // Without this the module still writes epoch numbers -- readable to a parser,
            // not to a reader.
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public String convert(Object result, Type returnType) {
        if (result == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(result);
        }
        catch (Exception e) {
            // The model must never be handed a stack trace: it reads a tool result as fact
            // and will pass it to a customer. This mirrors how tool failures are values
            // rather than exceptions everywhere else here -- see docs/tools.md.
            throw new IllegalStateException("Could not serialise a tool result of type "
                                            + result.getClass().getName(), e);
        }
    }
}
