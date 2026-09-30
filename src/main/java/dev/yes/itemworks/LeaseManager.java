package dev.yes.itemworks;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies and enforces a per-tool time limit, so a bought tool stops working eventually.
 *
 * <h2>Why a mod is needed at all</h2>
 *
 * EconomyCraft's {@code prices.json} hands out one fixed {@link ItemStack} for a component-matched
 * entry. The deadline therefore cannot live in the price file: every buyer would receive the identical
 * stack and share one clock. Instead the deadline is stamped onto each stack the first time this mod
 * sees it, which is what makes "24 hours after <em>your</em> purchase" true.
 *
 * <p>The stamp lives in {@link DataComponents#CUSTOM_DATA}, next to the tool's marker. A consequence
 * worth knowing: a stamped tool no longer exactly matches its price entry, because
 * {@code ItemStack.isSameItemSameComponents} is an exact test. {@code /worth} and {@code /sell} then
 * fall back to the plain base item, roughly the vanilla rate. That is a money sink rather than a
 * faucet — a purchase at the full shop price can be cashed out for a vanilla pickaxe and no more.
 *
 * <p>Both keys are written together, and the marker is re-asserted on every stamp, so a stack cannot
 * lose the marker by going through here.
 */
public final class LeaseManager {

    /** Key inside the stack's {@code minecraft:custom_data} holding the deadline, epoch millis. */
    public static final String KEY_UNTIL = "itemworksLeaseUntil";

    private static final Logger LOGGER = LoggerFactory.getLogger("itemworks");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * At or under this many minutes the countdown is drawn red instead of aqua, so a tool that is about
     * to vanish is obvious at a glance. Not configurable: it is a legibility threshold, not a policy.
     */
    private static final long LOW_TIME_MINUTES = 60;

    private static int ticksUntilSweep = 100;

    private LeaseManager() {
    }

    public static void register() {
        ticksUntilSweep = Math.max(20, ItemworksConfig.get().sweep_interval_ticks);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (--ticksUntilSweep > 0) return;
            ItemworksConfig config = ItemworksConfig.get();
            ticksUntilSweep = Math.max(20, config.sweep_interval_ticks);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sweep(player, config);
            }
        });

        // A tool bought while the player was offline, or picked up from a chest on login, gets
        // stamped or destroyed before they can swing it.
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> sweep(handler.getPlayer(), ItemworksConfig.get()));
    }

    private static void sweep(ServerPlayer player, ItemworksConfig config) {
        if (config.tools.isEmpty()) return;
        Inventory inventory = player.getInventory();
        // getContainerSize covers the 36 main slots plus the equipment slots (armour and offhand).
        int size = inventory.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            for (ToolDefinition tool : config.tools) {
                if (tool.lease().enabled() && tool.matcher().matches(stack)) {
                    apply(player, stack, tool);
                    break;
                }
            }
        }
    }

    private static void apply(ServerPlayer player, ItemStack stack, ToolDefinition tool) {
        ToolDefinition.Lease lease = tool.lease();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data != null ? data.copyTag() : new CompoundTag();

        Tag stored = tag.get(KEY_UNTIL);
        Optional<Long> deadline = stored == null ? Optional.empty() : stored.asLong();
        String label = labelOf(stack, tool);

        if (deadline.isEmpty()) {
            long until = System.currentTimeMillis() + (long) (lease.hours() * 3_600_000L);
            tag.putLong(KEY_UNTIL, until);
            // The marker shares this compound; re-assert it so stamping can never strip it.
            tag.putString(ToolMatcher.MARKER_KEY, tool.matcher().marker());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            if (lease.notifyOnStamp()) {
                player.sendSystemMessage(Component.literal(
                        "Your " + label + " lease runs for " + formatDuration(lease.hours())
                                + " (expires " + formatInstant(until) + ")")
                        .withStyle(ChatFormatting.AQUA));
            }
            return;
        }

        if (System.currentTimeMillis() < deadline.get()) {
            if (lease.showRemaining()) {
                writeCountdown(stack, lease, deadline.get());
            }
            return;
        }

        // Expired. Taking one from a stack of one destroys it; from a larger stack it just decrements.
        stack.shrink(1);
        LOGGER.debug("Lease expired for {} ({})", label, tool.id());
        if (lease.notifyOnExpire()) {
            player.sendSystemMessage(Component.literal(
                    "Your " + label + " lease expired and the tool was destroyed.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Keeps a live {@code HH:MM} countdown in the tool's lore, so a player can see how long they have
     * left without asking.
     *
     * <h2>Why the line is rewritten, not appended</h2>
     *
     * <p>{@link ItemLore} is an immutable record whose only mutator is {@code withLineAdded}, so a
     * countdown that appended on every tick would grow the lore until it hit {@code ItemLore.MAX_LINES}
     * and then silently stop. Instead the line is found by its configured prefix and replaced at the
     * same index, preserving every other line and its position.
     *
     * <p>The prefix is the only thing identifying the line. That is a deliberate trade: it is readable
     * with {@code /data get entity}, which is how an admin debugs everything else here, in exchange for
     * the knowledge that renaming {@code remaining_label} orphans the old line and appends a new one.
     * The old line is never removed automatically, because deciding that a line the admin wrote is
     * "stale" needs a marker the client can hide, and a marker that survives an anvil rename.
     *
     * <p>The sweep runs every {@code sweep_interval_ticks} (5s by default) but the displayed value only
     * changes once a minute, so the common path is a string compare and no write at all.
     */
    private static void writeCountdown(ItemStack stack, ToolDefinition.Lease lease, long deadline) {
        String label = lease.remainingLabel();
        if (label == null || label.isBlank()) {
            return;
        }
        // Floor, so a lease never reads as having more time than it has, and never goes negative.
        long minutes = Math.max(0L, (deadline - System.currentTimeMillis()) / 60_000L);
        String desired = label + formatRemaining(minutes);

        ItemLore lore = stack.get(DataComponents.LORE);
        List<Component> lines = lore == null ? List.of() : new ArrayList<>(lore.lines());

        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).getString();
            if (!text.startsWith(label)) {
                continue;
            }
            if (desired.equals(text)) {
                return;
            }
            lines.set(i, countdownLine(desired, minutes));
            stack.set(DataComponents.LORE, new ItemLore(List.copyOf(lines)));
            return;
        }

        // No countdown line yet, so this is the first write. A full lore is overwritten at the last
        // index rather than appended, because appending past MAX_LINES is what we are avoiding.
        if (lines.size() >= ItemLore.MAX_LINES) {
            lines.set(lines.size() - 1, countdownLine(desired, minutes));
        } else {
            lines.add(countdownLine(desired, minutes));
        }
        stack.set(DataComponents.LORE, new ItemLore(List.copyOf(lines)));
    }

    /**
     * Renders the remaining lease in words: {@code "21 hours 45 minutes"}.
     *
     * <p>Words rather than {@code "21:45"} because a bare colon-separated pair on an item tooltip reads
     * as a wall clock, and "Time left: 21:45" next to a real clock is genuinely ambiguous. Spelling the
     * units out removes the reading that matters.
     *
     * <p>Zero hours are dropped rather than printed, so a tool with 45 minutes left says "45 minutes" and
     * not "0 hours 45 minutes", and units are singularised when there is exactly one. A lease always has
     * at least one minute's worth of text, so the line is never empty.
     */
    private static String formatRemaining(long minutes) {
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours <= 0) {
            return rest + (rest == 1 ? " minute" : " minutes");
        }
        String text = hours + (hours == 1 ? " hour" : " hours");
        if (rest > 0) {
            text += " " + rest + (rest == 1 ? " minute" : " minutes");
        }
        return text;
    }

    private static Component countdownLine(String text, long minutes) {
        return Component.literal(text)
                .withStyle(minutes <= LOW_TIME_MINUTES ? ChatFormatting.RED : ChatFormatting.AQUA);
    }

    /** Prefers the item's own display name so the message reads the way the shop listed it. */
    private static String labelOf(ItemStack stack, ToolDefinition tool) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            String text = name.getString();
            if (!text.isBlank()) {
                return text;
            }
        }
        return tool.id();
    }

    private static String formatDuration(double hours) {
        Duration duration = Duration.ofMillis((long) (hours * 3_600_000L));
        long h = duration.toHours();
        long m = duration.toMinutesPart();
        if (h > 0) return h + "h" + (m > 0 ? " " + m + "m" : "");
        return m + "m";
    }

    private static String formatInstant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIMESTAMP);
    }
}
