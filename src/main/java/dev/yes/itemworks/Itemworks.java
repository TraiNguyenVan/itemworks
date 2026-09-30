package dev.yes.itemworks;

import dev.yes.itemworks.abilities.ExcavateOffsets;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Gives leased, configurable tools their abilities.
 *
 * <p>Replaces the Veinminer setup this mod was written to retire. Veinminer followed a vein by
 * repeatedly scanning for connected blocks, which meant its behaviour was a property of the mod rather
 * than something an admin could describe in a file. Here, behaviour is an {@link Ability} plus a config
 * block, so the shape of a tool is data.
 *
 * @see Ability for how to add behaviour
 */
public final class Itemworks implements DedicatedServerModInitializer {

    public static final String MOD_ID = "itemworks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Re-entrancy guard, held while an ability runs.
     *
     * <p>Chained {@code destroyBlock} calls fire {@code PlayerBlockBreakEvents.AFTER} synchronously,
     * so without this a single click would recurse: the neighbour break would trigger the ability
     * again, breaking its own neighbours, and a connected stone region would vanish in one tick. The
     * check happens before anything else so re-entry is a single array read.
     *
     * <p>ThreadLocal because a dedicated server can have several levels ticking on different threads,
     * and the depth is per-thread by definition. The array is a holder because ThreadLocal cannot
     * store a mutable int.
     */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    @Override
    public void onInitializeServer() {
        // Tier 2 registration. A new ability is one class plus one line here.
        AbilityRegistry.register(new ExcavateOffsets());

        ItemworksConfig.load();
        LeaseManager.register();

        // AFTER, never BEFORE. See the class notes on ExcavateOffsets and on ShopGuard interaction.
        PlayerBlockBreakEvents.AFTER.register(Itemworks::onBlockBroken);

        LOGGER.info("itemworks ready — {} ability(ies) registered: {}",
                AbilityRegistry.ids().size(), AbilityRegistry.ids());
    }

    private static void onBlockBroken(Level level,
                                       Player player,
                                       BlockPos pos,
                                       BlockState state,
                                       BlockEntity blockEntity) {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) {
            // We are inside a chained break that this ability caused.
            return;
        }
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        List<ToolDefinition> tools = ItemworksConfig.get().tools;
        if (tools.isEmpty()) {
            return;
        }

        ItemStack stack = serverPlayer.getMainHandItem();
        if (stack.isEmpty()) {
            return;
        }

        for (ToolDefinition tool : tools) {
            if (!tool.matcher().matches(stack)) {
                continue;
            }
            for (Map.Entry<String, AbilityConfig> entry : tool.abilities().entrySet()) {
                if (!entry.getValue().enabled()) {
                    continue;
                }
                Ability ability = AbilityRegistry.get(entry.getKey());
                if (ability == null) {
                    // Already reported once at load; do not repeat it every swing.
                    continue;
                }
                depth[0]++;
                try {
                    ability.onBlockBreak(new AbilityContext(
                            serverPlayer, level, pos, state, stack, tool, entry.getKey(), entry.getValue()));
                } catch (RuntimeException e) {
                    // One misbehaving ability must not be able to break a player's mining loop.
                    LOGGER.error("Ability \"{}\" on tool \"{}\" threw", entry.getKey(), tool.id(), e);
                } finally {
                    depth[0]--;
                }
            }
            // One matching tool is enough; two definitions claiming the same stack is a config
            // mistake, and picking the first is the same thing ToolMatcher already does elsewhere.
            return;
        }
    }
}
