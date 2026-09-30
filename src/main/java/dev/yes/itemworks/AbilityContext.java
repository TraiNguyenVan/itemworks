package dev.yes.itemworks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Everything an {@link Ability} is allowed to see, passed as one immutable bundle so that adding an
 * ability never means widening a long parameter list.
 *
 * @param player     the breaking player, already known to be a {@link ServerPlayer}
 * @param level      the level the break happened in
 * @param pos        the position of the block that was actually broken (not the one looked at)
 * @param state      the state of the broken block, as it was before the break
 * @param stack      the player's main-hand stack at the moment of the break
 * @param tool       the tool definition that matched, including its lease
 * @param abilityId  the config key this ability was invoked under
 * @param config     that key's raw config, with lazily compiled values cached
 */
public record AbilityContext(
        ServerPlayer player,
        Level level,
        BlockPos pos,
        BlockState state,
        ItemStack stack,
        ToolDefinition tool,
        String abilityId,
        AbilityConfig config
) {
}
