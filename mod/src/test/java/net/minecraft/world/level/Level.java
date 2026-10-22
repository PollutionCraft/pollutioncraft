package net.minecraft.world.level;

/**
 * Stand-in for the real Minecraft class, used only by the architecture fixtures. Minecraft is not on
 * mod's test classpath (its pinned slf4j conflicts with ArchUnit's), and the rules look at package
 * names only, so a class with the same name is enough.
 */
public final class Level {}
