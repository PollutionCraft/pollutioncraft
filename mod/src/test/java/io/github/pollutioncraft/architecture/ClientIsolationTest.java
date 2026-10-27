package io.github.pollutioncraft.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A dedicated server must never load a client class (task 030). Static half of the proof; the runtime half is the
 * class-load check on {@code runGameTestServer} in {@code mod/build.gradle}. The rules take the root package so the
 * same rule runs against production and against fixtures, which prove each rule can fail.
 */
class ClientIsolationTest {
    private static final String BASE = "io.github.pollutioncraft";
    private static final String FIXTURES = "io.github.pollutioncraft.architecture.fixtures.clientisolation.";

    private static JavaClasses production;

    /** Nothing outside {@code mod.client} may reference it, except the one class that registers the payload handler. */
    static ArchRule onlyTheRegistrationReferencesClientClasses(String base) {
        return noClasses().that().resideOutsideOfPackage(base + ".mod.client..")
                .and().doNotHaveFullyQualifiedName(base + ".mod.overlay.OverlayRegistration")
                .should().dependOnClassesThat().resideInAPackage(base + ".mod.client..")
                .allowEmptyShould(true)
                .as("only mod.overlay.OverlayRegistration may reference mod.client.*");
    }

    /** Minecraft's and NeoForge's client packages belong to the client package alone. */
    static ArchRule clientMinecraftOnlyInClientPackage(String base) {
        return noClasses().that().resideOutsideOfPackage(base + ".mod.client..")
                .should().dependOnClassesThat().resideInAnyPackage("net.minecraft.client..", "net.neoforged.neoforge.client..")
                .allowEmptyShould(true)
                .as("only mod.client.* may reference net.minecraft.client.* or net.neoforged.neoforge.client.*");
    }

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(location -> !location.contains("/architecture/fixtures/"))
                .importPackages(BASE);
    }

    @Test
    void productionKeepsClientClassesBehindTheRegistration() {
        onlyTheRegistrationReferencesClientClasses(BASE).check(production);
    }

    @Test
    void productionKeepsMinecraftClientClassesInTheClientPackage() {
        clientMinecraftOnlyInClientPackage(BASE).check(production);
    }

    private static AssertionError violation(ArchRule rule, String fixtureRoot) {
        JavaClasses classes = new ClassFileImporter().importPackages(FIXTURES + fixtureRoot);
        assertFalse(classes.isEmpty(), "fixture set is empty: " + fixtureRoot);
        return assertThrows(AssertionError.class, () -> rule.check(classes));
    }

    @Test
    void referencingClientClassesFromElsewhereFailsAndNamesOnlyTheOffender() {
        AssertionError failure = violation(onlyTheRegistrationReferencesClientClasses(FIXTURES + "violation"), "violation");

        assertTrue(failure.getMessage().contains("Rogue"), failure.getMessage());
        assertFalse(failure.getMessage().contains("OverlayRegistration."), "the registration point is allowed:\n" + failure.getMessage());
    }

    @Test
    void touchingMinecraftClientOutsideTheClientPackageFails() {
        AssertionError failure = violation(clientMinecraftOnlyInClientPackage(FIXTURES + "violationminecraft"), "violationminecraft");

        assertTrue(failure.getMessage().contains("TouchesMinecraft"), failure.getMessage());
    }

    @Test
    void aCompliantLayoutPassesBothRules() {
        JavaClasses classes = new ClassFileImporter().importPackages(FIXTURES + "compliant");
        assertFalse(classes.isEmpty());
        assertDoesNotThrow(() -> onlyTheRegistrationReferencesClientClasses(FIXTURES + "compliant").check(classes));
        assertDoesNotThrow(() -> clientMinecraftOnlyInClientPackage(FIXTURES + "compliant").check(classes));
    }
}
