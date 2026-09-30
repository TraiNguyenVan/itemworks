package dev.yes.itemworks;

import java.util.Map;

/**
 * One sellable tool: what stack counts as it, how long its lease runs, and which abilities it carries.
 *
 * <p>Adding a tool to the server is a config block and a shop entry. No new class, no rebuild — that is
 * the whole point of keeping behaviour behind {@link Ability} rather than hard-coding it to a tool.
 */
public record ToolDefinition(
        String id,
        ToolMatcher matcher,
        Lease lease,
        Map<String, AbilityConfig> abilities
) {

    /**
     * Lease terms for one tool.
     *
     * <p>Fractional hours are allowed, which is the only sane way to test expiry: 0.01 is 36 seconds.
     *
     * @param hours          lease length; values &lt;= 0 are rejected at load
     * @param notifyOnStamp  tell the player when their lease starts
     * @param notifyOnExpire tell the player when their tool is destroyed
     * @param showRemaining  keep a live {@code HH:MM} countdown in the item's lore
     * @param remainingLabel prefix that marks which lore line the countdown owns
     */
    public record Lease(
            boolean enabled,
            double hours,
            boolean notifyOnStamp,
            boolean notifyOnExpire,
            boolean showRemaining,
            String remainingLabel
    ) {
    }

    public boolean hasAbility(String abilityId) {
        AbilityConfig config = abilities.get(abilityId);
        return config != null && config.enabled();
    }

    @Override
    public String toString() {
        return "ToolDefinition{" + id + " -> " + matcher + ", abilities=" + abilities.keySet() + "}";
    }
}
