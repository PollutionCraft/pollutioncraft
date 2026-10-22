package io.github.pollutioncraft.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Proves each rule bites: it must fail on a deliberate violation (naming the offender, and only the
 * offender) and pass on a compliant set. Fixtures live under {@code ...architecture.fixtures} and are
 * test classes, so the production check and the jar never see them.
 */
class ArchitectureRulesSelfTest {
    private static final String FIXTURES = "io.github.pollutioncraft.architecture.fixtures.";

    private static void assertViolates(
            Function<String, ArchRule> rule, String fixtureRoot, String offender, String innocent) {
        String root = FIXTURES + fixtureRoot;
        JavaClasses classes = new ClassFileImporter().importPackages(root);
        assertFalse(classes.isEmpty(), "fixture set is empty: " + root);
        AssertionError failure = assertThrows(AssertionError.class, () -> rule.apply(root).check(classes));
        assertTrue(failure.getMessage().contains(offender), () -> "message does not name " + offender + ":\n" + failure.getMessage());
        if (innocent != null) {
            // "Innocent." is how ArchUnit prints a violating origin member; the class may still appear as a target.
            assertFalse(failure.getMessage().contains(innocent + "."), () -> "message wrongly blames " + innocent + ":\n" + failure.getMessage());
        }
    }

    private static void assertCompliant(Function<String, ArchRule> rule, String fixtureRoot) {
        String root = FIXTURES + fixtureRoot;
        JavaClasses classes = new ClassFileImporter().importPackages(root);
        assertFalse(classes.isEmpty(), "fixture set is empty: " + root);
        assertDoesNotThrow(() -> rule.apply(root).check(classes));
    }

    @Test
    void corePurityFailsOnMinecraftReference() {
        assertViolates(ArchitectureRules::corePurity, "corepurity.violation", "BadCore", null);
    }

    @Test
    void corePurityPassesOnPlainJava() {
        assertCompliant(ArchitectureRules::corePurity, "corepurity.compliant");
    }

    @Test
    void apiPurityFailsOnCoreReference() {
        assertViolates(ArchitectureRules::apiPurity, "apipurity.violation", "BadApi", "GoodApi");
    }

    @Test
    void apiPurityFailsOnUnrelatedLibrary() {
        assertViolates(ArchitectureRules::apiPurity, "apipurity.violationlibrary", "BadLibraryApi", null);
    }

    @Test
    void apiPurityPassesOnMinecraftJdkAndOwnApi() {
        assertCompliant(ArchitectureRules::apiPurity, "apipurity.compliant");
    }

    @Test
    void compatIsolationFailsOnModClassReferencingCompat() {
        assertViolates(ArchitectureRules::compatIsolation, "compatisolation.violation", "RogueLoader", "CompatBootstrap");
    }

    @Test
    void compatIsolationAllowsOnlyCompatBootstrap() {
        assertCompliant(ArchitectureRules::compatIsolation, "compatisolation.compliant");
    }

    @Test
    void adapterImportsFailOnCoreReference() {
        assertViolates(ArchitectureRules::adapterImports, "adapterimports.violation", "BadAdapter", "GoodAdapter");
    }

    @Test
    void adapterImportsFailOnSiblingAdapter() {
        assertViolates(ArchitectureRules::adapterImports, "adapterimports.crosscompat", "NosyAdapter", "GtAdapter");
    }

    @Test
    void adapterImportsPassOnApiOwnPackageAndMinecraft() {
        assertCompliant(ArchitectureRules::adapterImports, "adapterimports.compliant");
    }
}
