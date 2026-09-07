package dev.merlionos.customerservice.evaluation;

import dev.merlionos.customerservice.chat.TurnEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The rubric applied to what a turn produced. Pure, so the rules are testable without a model. */
final class Scoring {

    /** What the events of one turn amounted to. */
    record Turn(List<String> retrieved, List<String> tools, String answer, Integer inputTokens, Integer outputTokens,
                Long millis, boolean failed) {

        static Turn of(List<TurnEvent> events) {
            List<String> retrieved = new ArrayList<>();
            List<String> tools = new ArrayList<>();
            StringBuilder answer = new StringBuilder();
            Integer in = null;
            Integer out = null;
            Long millis = null;
            boolean failed = false;
            boolean textSinceTool = false;
            for (TurnEvent event : events) {
                switch (event) {
                    case TurnEvent.Retrieval retrieval -> retrieval.passages().forEach(p -> retrieved.add(p.entryId()));
                    case TurnEvent.ToolCall tool -> {
                        tools.add(tool.tool());
                        textSinceTool = false;
                    }
                    case TurnEvent.Token token -> {
                        // The paragraph break at the seam between a turn's two model calls, as the clients render it.
                        if (!token.text().isEmpty() && !textSinceTool && !answer.isEmpty()) {
                            answer.append("\n\n");
                        }
                        if (!token.text().isEmpty()) {
                            textSinceTool = true;
                        }
                        answer.append(token.text());
                    }
                    case TurnEvent.Usage usage -> {
                        in = usage.inputTokens();
                        out = usage.outputTokens();
                        millis = usage.millis();
                    }
                    case TurnEvent.Failure failure -> failed = true;
                }
            }
            return new Turn(retrieved, tools, answer.toString(), in, out, millis, failed);
        }
    }

    record Score(boolean retrievalHit, boolean answerPass, boolean toolPass, List<String> failures) {

        boolean passed() {
            return retrievalHit && answerPass && toolPass;
        }
    }

    private Scoring() {
    }

    static Score score(GoldenCase golden, Turn turn) {
        List<String> failures = new ArrayList<>();
        if (turn.failed()) {
            failures.add("the turn failed before an answer");
        }
        boolean retrievalHit = true;
        for (String expected : golden.expectedEntryIds()) {
            if (!turn.retrieved().contains(expected)) {
                retrievalHit = false;
                failures.add("did not retrieve " + expected);
            }
        }
        String answer = normalise(turn.answer());
        boolean answerPass = !turn.failed();
        for (String phrase : golden.mustContain()) {
            if (!answer.contains(normalise(phrase))) {
                answerPass = false;
                failures.add("answer lacks \"" + phrase + "\"");
            }
        }
        if (!golden.anyOf().isEmpty() && golden.anyOf().stream().noneMatch(phrase -> answer.contains(normalise(phrase)))) {
            answerPass = false;
            failures.add("answer has none of " + golden.anyOf());
        }
        for (String phrase : golden.mustNotContain()) {
            if (answer.contains(normalise(phrase))) {
                answerPass = false;
                failures.add("answer contains \"" + phrase + "\"");
            }
        }
        if (golden.expectRefusal() && golden.anyOf().isEmpty() && golden.mustContain().isEmpty()) {
            // A refusal case with no phrases of its own is judged by not having invented: the
            // must-not-contain list is where the facts it must not claim go.
            failures.add("a refusal case needs phrases to look for (anyOf) or facts it must not claim (mustNotContain)");
            answerPass = false;
        }
        boolean toolPass = golden.expectTool() == null || turn.tools().contains(golden.expectTool());
        if (!toolPass) {
            failures.add("tool " + golden.expectTool() + " did not run" + (turn.tools().isEmpty() ? "" : "; ran " + turn.tools()));
        }
        return new Score(retrievalHit, answerPass, toolPass, failures);
    }

    /**
     * Case-folded, whitespace collapsed, full-width digits narrowed, en and em dashes made
     * hyphens, and the space between a digit and a CJK character dropped: "30  days" and
     * "30 days" agree, "3–5" and "3-5" agree, and "30 分钟" and "30分钟" agree, because a model
     * writes each of those both ways and none of them is a different answer.
     */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') {
                out.append((char) ('0' + (c - '０')));
            }
            else if (c == '　' || Character.isWhitespace(c)) {
                out.append(' ');
            }
            else if (c == '–' || c == '—' || c == '‑' || c == '−') {
                out.append('-');
            }
            else {
                out.append(c);
            }
        }
        String collapsed = out.toString().toLowerCase(Locale.ROOT).replaceAll(" +", " ").strip();
        StringBuilder joined = new StringBuilder(collapsed.length());
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            if (c == ' ' && i > 0 && i < collapsed.length() - 1 && (cjk(collapsed.charAt(i - 1)) || cjk(collapsed.charAt(i + 1)))) {
                continue;
            }
            joined.append(c);
        }
        return joined.toString();
    }

    private static boolean cjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }
}
