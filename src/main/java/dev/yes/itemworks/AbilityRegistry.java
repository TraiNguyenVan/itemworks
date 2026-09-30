package dev.yes.itemworks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The set of abilities this mod knows how to run, keyed by config id.
 *
 * <p>Deliberately static and populated once at startup. A config referencing an id that was never
 * registered is reported at load time and then ignored at runtime, so a typo degrades that one ability
 * instead of spamming the log on every swing.
 */
public final class AbilityRegistry {

    private static final Map<String, Ability> REGISTRY = new LinkedHashMap<>();

    private AbilityRegistry() {
    }

    /**
     * Registers an ability under its own {@link Ability#id()}.
     *
     * @throws IllegalStateException if the id is taken, because silently overwriting would make
     *                               registration order decide behaviour
     */
    public static void register(Ability ability) {
        Ability existing = REGISTRY.putIfAbsent(ability.id(), ability);
        if (existing != null) {
            throw new IllegalStateException("Ability id \"" + ability.id() + "\" is already registered by "
                    + existing.getClass().getName());
        }
    }

    public static Ability get(String id) {
        return REGISTRY.get(id);
    }

    public static boolean isKnown(String id) {
        return REGISTRY.containsKey(id);
    }

    public static Set<String> ids() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }
}
