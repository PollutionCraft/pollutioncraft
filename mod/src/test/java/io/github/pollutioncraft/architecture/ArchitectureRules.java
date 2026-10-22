package io.github.pollutioncraft.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * The four build checks of design §3. Each takes the root package so the same rule runs against
 * production ({@code io.github.pollutioncraft}) and against the fixtures in the self-tests.
 */
final class ArchitectureRules {
    private static final String[] MINECRAFT_AND_LOADER = {"net.minecraft..", "com.mojang..", "net.neoforged.."};

    /**
     * Libraries that Minecraft and NeoForge expose in their public signatures, so API types may use
     * them. Adding to this list is a deliberate API decision.
     */
    private static final String[] EXPOSED_LIBRARIES = {
        "com.google.common..", "com.google.gson..", "it.unimi.dsi.fastutil..", "org.joml..",
        "org.slf4j..", "org.apache.logging.log4j..", "org.jetbrains.annotations..", "io.netty.."
    };

    private ArchitectureRules() {}

    /** 1. Core purity: no Minecraft or loader classes. */
    static ArchRule corePurity(String base) {
        return noClasses().that().resideInAPackage(base + ".core..")
                .should().dependOnClassesThat().resideInAnyPackage(MINECRAFT_AND_LOADER)
                .as("core.* must not reference Minecraft or loader classes");
    }

    /** 2. API purity: JDK, Minecraft, NeoForge, the libraries they expose, and our own api only. */
    static ArchRule apiPurity(String base) {
        String[] allowed = concat(MINECRAFT_AND_LOADER, EXPOSED_LIBRARIES, new String[] {"java..", "javax..", base + ".api.."});
        return classes().that().resideInAPackage(base + ".api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(allowed)
                .as("api.* must reference only the JDK, Minecraft, NeoForge and api.*");
    }

    /**
     * 3. Compat isolation: only {@code mod.CompatBootstrap} may reference {@code compat.*}. Until task 012
     * there is no compat package, so "no classes matched" must not fail the rule.
     */
    static ArchRule compatIsolation(String base) {
        return noClasses().that().resideInAPackage(base + ".mod..")
                .and().doNotHaveFullyQualifiedName(base + ".mod.CompatBootstrap")
                .should().dependOnClassesThat().resideInAPackage(base + ".compat..")
                .allowEmptyShould(true)
                .as("only mod.CompatBootstrap may reference compat.*");
    }

    /**
     * 4. Adapter imports: of our packages, compat classes may use only api.* and their own
     * {@code compat.<modid>} package. Vacuous until the first adapter exists, hence allowEmptyShould.
     */
    static ArchRule adapterImports(String base) {
        return classes().that().resideInAPackage(base + ".compat..")
                .should(onlyUseApiAndOwnAdapterPackage(base))
                .allowEmptyShould(true)
                .as("compat.* may reference only api.* and its own compat.<modid> package");
    }

    private static ArchCondition<JavaClass> onlyUseApiAndOwnAdapterPackage(String base) {
        return new ArchCondition<>("reference only api.* and their own compat.<modid> package") {
            @Override
            public void check(JavaClass adapter, ConditionEvents events) {
                String ownPackage = adapterPackage(base, adapter.getPackageName());
                for (Dependency dependency : adapter.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    if (isOurs(base, target) && !isIn(target, base + ".api") && !isIn(target, ownPackage)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** {@code <base>.compat.<modid>} for a class in it; the {@code compat} package itself if it has no modid. */
    private static String adapterPackage(String base, String packageName) {
        String compat = base + ".compat";
        String[] below = packageName.substring(Math.min(packageName.length(), compat.length() + 1)).split("\\.", 2);
        return below[0].isEmpty() ? compat : compat + "." + below[0];
    }

    private static boolean isOurs(String base, String packageName) {
        return isIn(packageName, base);
    }

    private static boolean isIn(String packageName, String root) {
        return packageName.equals(root) || packageName.startsWith(root + ".");
    }

    private static String[] concat(String[]... parts) {
        return java.util.Arrays.stream(parts).flatMap(java.util.Arrays::stream).toArray(String[]::new);
    }
}
