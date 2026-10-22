package io.github.pollutioncraft.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Design §3: the four build checks, run against the real core, api and mod classes. */
class ProductionArchitectureTest {
    private static final String BASE = "io.github.pollutioncraft";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(location -> !location.contains("/architecture/fixtures/"))
                .importPackages(BASE);
    }

    @Test
    void corePurity() {
        ArchitectureRules.corePurity(BASE).check(production);
    }

    @Test
    void apiPurity() {
        ArchitectureRules.apiPurity(BASE).check(production);
    }

    @Test
    void compatIsolation() {
        ArchitectureRules.compatIsolation(BASE).check(production);
    }

    @Test
    void adapterImports() {
        ArchitectureRules.adapterImports(BASE).check(production);
    }
}
