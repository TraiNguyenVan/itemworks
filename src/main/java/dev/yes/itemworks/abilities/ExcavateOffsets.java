package dev.yes.itemworks.abilities;

import dev.yes.itemworks.Ability;
import dev.yes.itemworks.AbilityConfig;
import dev.yes.itemworks.AbilityContext;
import dev.yes.itemworks.BlockFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Breaks the eight blocks surrounding the one the player just broke, in a plane chosen by config.
 *
 * <h2>Planes</h2>
 *
 * {@code "plane": "fixed"} uses the {@code offsets} list verbatim. That is the shape control that makes
 * this a Tier 1 ability: a 3x3x3 tunnel or a plus-shape is a config edit, not a code change.
 *
 * <p>{@code "plane": "view"} instead picks the plane from where the player is looking, which is what
 * actually feels right in the hand:
 *
 * <ul>
 *   <li>Looking up or down steeply — more than {@code view_threshold} off level — gives a horizontal
 *       ring, so a player digging into the ground clears the floor in front of them and nothing above
 *       or below it.</li>
 *   <li>Looking roughly level gives a vertical ring in the plane being faced, so breaking one block of
 *       a wall clears the surrounding 3x3 of that same wall and nothing in front of or behind it.</li>
 * </ul>
 *
 * <p>Either way the ring never leaves its own plane, so the tool stays one block deep in the direction
 * you are digging. {@code view_threshold} is the sine of the pitch at which it switches: 0.5 is about
 * 30 degrees off level.
 *
 * <h2>Claim safety</h2>
 *
 * Blocks are removed with {@link net.minecraft.server.level.ServerPlayerGameMode#destroyBlock}, not by
 * setting them to air. That matters: Fabric's {@code ServerPlayerGameModeMixin} injects
 * {@code PlayerBlockBreakEvents.BEFORE} and {@code .AFTER} into {@code destroyBlock} itself, so every
 * single chained break re-runs ShopGuard's protection check. A 3x3 that straddles a claim border
 * therefore breaks the blocks the player owns and leaves the neighbours alone.
 *
 * <p>Doing it the other way — {@code setBlock(air)} — would be faster and completely unprotected,
 * because no break event fires and ShopGuard would never see it.
 */
public final class ExcavateOffsets implements Ability {

    public static final String ID = "excavate_offsets";

    private static final String PLANE_FIXED = "fixed";
    private static final String PLANE_VIEW = "view";

    private static final Logger LOGGER = LoggerFactory.getLogger("itemworks");

    /** One warning per tool, not one per swing. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onBlockBreak(AbilityContext ctx) {
        AbilityConfig config = ctx.config();

        if (config.flag("require_sneak", true) && !ctx.player().isShiftKeyDown()) {
            return;
        }

        BlockFilter filter = config.blockFilter();
        if (filter.isEmpty()) {
            warnOnce(ctx, "resolved no blocks from its \"blocks\" list, so it can never fire");
            return;
        }
        // The ability only triggers on an eligible block, which is what stops a shovel from
        // stone-mining and a pickaxe from tunnelling through dirt.
        if (!filter.matches(ctx.state())) {
            return;
        }

        int[][] offsets = resolveOffsets(ctx, config);
        if (offsets.length == 0) {
            warnOnce(ctx, "produced an empty offset set, so it can never fire");
            return;
        }

        ItemStack stack = ctx.stack();
        // Stop before the tool is spent, mirroring the previous mod's behaviour: a 3x3 costs 9
        // durability, and breaking through a 1-durability tool would destroy it outright.
        if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
            return;
        }

        boolean allowBlockEntities = config.flag("include_block_entities", false);
        ServerPlayer player = ctx.player();
        BlockPos origin = ctx.pos();
        int broken = 0;

        for (int[] offset : offsets) {
            // A fresh BlockPos per offset. BlockPos is immutable and offset() returns a Vec3i, so
            // there is nothing to reuse safely here.
            BlockPos target = new BlockPos(
                    origin.getX() + offset[0],
                    origin.getY() + offset[1],
                    origin.getZ() + offset[2]);

            BlockState targetState = ctx.level().getBlockState(target);
            if (!filter.matches(targetState)) {
                continue;
            }
            // Chests, signs and other block entities are skipped by default. Their contents are not
            // ours to redistribute, and a wide swing opening them would be a nasty surprise.
            if (targetState.hasBlockEntity() && !allowBlockEntities) {
                continue;
            }
            if (player.gameMode.destroyBlock(target)) {
                broken++;
            }
        }

        if (broken > 0) {
            LOGGER.debug("{}: broke {} block(s) around {} for tool \"{}\"",
                    ID, broken, origin, ctx.tool().id());
        }
    }

    private void warnOnce(AbilityContext ctx, String problem) {
        if (warned.add(ctx.tool().id())) {
            LOGGER.warn("Ability \"{}\" on tool \"{}\" {}", ID, ctx.tool().id(), problem);
        }
    }

    private int[][] resolveOffsets(AbilityContext ctx, AbilityConfig config) {
        String plane = config.string("plane", PLANE_FIXED);
        if (PLANE_VIEW.equalsIgnoreCase(plane)) {
            float threshold = (float) config.number("view_threshold", 0.5);
            return viewPlane(ctx.player(), threshold);
        }
        if (!PLANE_FIXED.equalsIgnoreCase(plane)) {
            warnOnce(ctx, "has unknown plane \"" + plane + "\", falling back to \"" + PLANE_FIXED + "\"");
        }
        return config.offsets();
    }

    /**
     * The eight neighbours of the origin, in the plane the player is looking along.
     *
     * <p>Horizontal case: a ring in x/z at the broken block's own y, so the floor or ceiling clears but
     * nothing above or below the ring is disturbed.
     *
     * <p>Vertical case: a ring spanning the two horizontal axes perpendicular to the player's facing,
     * plus the blocks directly above and below. That is the 3x3 of the wall being looked at, and it
     * leaves the blocks in front of and behind the origin alone.
     */
    private static int[][] viewPlane(ServerPlayer player, float threshold) {
        if (Math.abs(player.getLookAngle().y) >= threshold) {
            int[][] ring = new int[8][];
            int i = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        // The origin is already broken; re-breaking it would be a wasted event.
                        continue;
                    }
                    ring[i++] = new int[]{dx, 0, dz};
                }
            }
            return ring;
        }

        // getDirection() is the horizontal cardinal the player faces, so its two rotations are exactly
        // the left and right axes of the wall in front of them.
        Direction facing = player.getDirection();
        List<int[]> ring = new ArrayList<>(8);
        for (Direction side : new Direction[]{facing.getCounterClockWise(), facing.getClockWise()}) {
            for (int dy = -1; dy <= 1; dy++) {
                ring.add(new int[]{side.getStepX(), dy, side.getStepZ()});
            }
        }
        ring.add(new int[]{0, 1, 0});
        ring.add(new int[]{0, -1, 0});
        return ring.toArray(new int[0][]);
    }
}
