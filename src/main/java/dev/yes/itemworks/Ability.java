package dev.yes.itemworks;

/**
 * One behaviour itemworks can attach to a tool.
 *
 * <p>There are two ways to add behaviour, and the split is deliberate:
 *
 * <ul>
 *   <li><b>Tier 1, no code.</b> Reconfigure an ability that already exists. {@code excavate_offsets}
 *       takes its shape from an offset list, so a 3x3, a 3x3x3, a plus-shape or a tunnel are all
 *       pure config edits. This is where most new tools should land.</li>
 *   <li><b>Tier 2, new class.</b> Write an implementation for behaviour that is not a list of block
 *       positions — tree felling, tilling, smelting. Implement this interface and add exactly one
 *       {@link AbilityRegistry#register(Ability)} call in {@link Itemworks}. The core event loop and
 *       the lease never need editing again.</li>
 * </ul>
 *
 * <p>Implementations are called only from the server thread, from inside
 * {@link net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents#AFTER}, while a re-entrancy guard
 * is held. Chained breaks fire the event again, but the guard makes those calls return immediately,
 * so an ability never has to defend against its own re-entry.
 */
public interface Ability {

    /** Config key this ability is invoked under, e.g. {@code "excavate_offsets"}. Stable, because it is in saved config. */
    String id();

    /**
     * Runs after a player has successfully broken a block, while holding a tool whose config enabled
     * this ability. Exceptions thrown here are caught and logged by the caller rather than allowed to
     * escape into the block-break event, so a broken ability cannot wedge a player's mining.
     */
    void onBlockBreak(AbilityContext ctx);
}
