package dev.merlionos.customerservice.rag.api;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.Optional;

/**
 * How a tenant reaches the vector store from the advisor chain. {@code QuestionAnswerAdvisor}
 * accepts one thing per request: a filter expression, as text, in its context. So the chat
 * side puts {@code tenant == 'acme'} there, and the store on the other side ({@code
 * ActiveVersionVectorStore}, or the remote one in a {@code chat} process) reads the tenant
 * out of the filter, resolves that tenant's active version, and searches that. The key is
 * not a metadata field of any document: documents are confined by {@code corpus_version},
 * and a version belongs to one tenant.
 */
public final class TenantFilter {

    public static final String KEY = "tenant";

    private TenantFilter() {
    }

    /** The advisor's per-request filter text. Tenant ids are {@code [a-z0-9-]}, so no quoting is needed. */
    public static String text(String tenantId) {
        return KEY + " == '" + tenantId + "'";
    }

    public static Filter.Expression expression(String tenantId) {
        return new FilterExpressionBuilder().eq(KEY, tenantId).build();
    }

    /** The tenant named by the expression, if it is {@code tenant == x} or {@code x AND tenant == y}. */
    public static Optional<String> tenantOf(Filter.Expression expression) {
        if (expression == null) {
            return Optional.empty();
        }
        if (expression.type() == Filter.ExpressionType.EQ && expression.left() instanceof Filter.Key key
                && KEY.equals(key.key()) && expression.right() instanceof Filter.Value value) {
            return Optional.of(String.valueOf(value.value()));
        }
        if (expression.type() == Filter.ExpressionType.AND) {
            return tenantOf((Filter.Expression) expression.left())
                    .or(() -> tenantOf((Filter.Expression) expression.right()));
        }
        return Optional.empty();
    }

    /** The expression with the tenant clause removed; null when nothing else was asked. */
    public static Filter.Expression without(Filter.Expression expression) {
        if (expression == null || tenantOf(expression).isEmpty()) {
            return expression;
        }
        if (expression.type() == Filter.ExpressionType.EQ) {
            return null;
        }
        Filter.Expression left = without((Filter.Expression) expression.left());
        Filter.Expression right = without((Filter.Expression) expression.right());
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return new Filter.Expression(Filter.ExpressionType.AND, left, right);
    }
}
