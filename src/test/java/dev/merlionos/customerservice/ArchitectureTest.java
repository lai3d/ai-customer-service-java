package dev.merlionos.customerservice;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The dependency direction the deployment targets rely on, enforced here rather than by a
 * Maven module split (ADR 001, decision 6). If one of these fails, the {@code chat} process
 * would carry a business module's implementation, or a business module would need something
 * only a chat process has.
 */
class ArchitectureTest {

    private static final String ROOT = "dev.merlionos.customerservice";

    /** Everything a {@code chat} process runs and nothing else does. */
    private static final String[] CHAT_SIDE = {
            ROOT + ".admin..", ROOT + ".chat..", ROOT + ".clients..", ROOT + ".config..", ROOT + ".cost..",
            ROOT + ".orders..", ROOT + ".provider..", ROOT + ".tools.."};

    /** The two roles that can run without the chat side, and their contracts. */
    private static final String TICKET_IMPL = ROOT + ".ticket";
    private static final String KNOWLEDGE_IMPL = ROOT + ".rag";
    private static final String[] BUSINESS = {ROOT + ".ticket..", ROOT + ".rag.."};

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("the chat side reaches ticket and knowledge only through their api packages")
    void chatSideDependsOnBusinessModulesOnlyThroughTheirApi() {
        noClasses().that().resideInAnyPackage(CHAT_SIDE)
                .should().dependOnClassesThat().resideInAnyPackage(TICKET_IMPL, KNOWLEDGE_IMPL)
                .because("a chat process must be able to run with those implementations absent")
                .check(classes);
    }

    @Test
    @DisplayName("ticket and knowledge know nothing about tools, turns, events or the chat side")
    void businessModulesDoNotDependOnTheChatSide() {
        noClasses().that().resideInAnyPackage(BUSINESS)
                .should().dependOnClassesThat().resideInAnyPackage(CHAT_SIDE)
                .because("a ticket or knowledge process runs without the chat side present")
                .check(classes);

        noClasses().that().resideInAnyPackage(BUSINESS)
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.ai.chat.model.ToolContext")
                .orShould().dependOnClassesThat().resideInAPackage("org.springframework.ai.tool..")
                .because("tool adaptation is the chat side's job; business modules return values")
                .check(classes);
    }

    @Test
    @DisplayName("the target mechanism depends on nothing of ours, so every role can use it")
    void targetMechanismIsFoundational() {
        noClasses().that().resideInAPackage(ROOT + ".target..")
                .should().dependOnClassesThat(
                        com.tngtech.archunit.base.DescribedPredicate.describe("our other packages",
                                javaClass -> javaClass.getPackageName().startsWith(ROOT)
                                        && !javaClass.getPackageName().startsWith(ROOT + ".target")))
                .check(classes);
    }
}
