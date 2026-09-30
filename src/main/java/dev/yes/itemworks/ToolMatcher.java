package dev.yes.itemworks;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Decides whether a stack is "the" shop tool for a tool definition.
 *
 * <p>Two things must both hold: the stack is the configured base item, and it carries this mod's marker
 * in its custom data. The marker is what makes the match safe.
 *
 * <p>Matching on the display name instead, which the previous lease mod did, breaks the moment an admin
 * renames the shop item in {@code prices.json}: the ability silently stops working for everyone, with
 * no error anywhere. It is also far too loose, because a player can rename their own pickaxe. The
 * marker is set once by the price entry and is not something display text can drift away from.
 *
 * <p>The base-item check is what stops a player's own netherite pickaxe from being treated as a leased
 * tool, which matters because {@link LeaseManager} destroys expired stacks.
 */
public final class ToolMatcher {

    /** Key inside the stack's {@code minecraft:custom_data} compound that holds the marker value. */
    public static final String MARKER_KEY = "itemworks";

    private final Item item;
    private final String marker;

    public ToolMatcher(Item item, String marker) {
        this.item = item;
        this.marker = marker;
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        // 26.3 dropped ItemStack.is(Item) in favour of a Predicate<Holder<Item>> overload, so an
        // identity check on the resolved item is the direct equivalent.
        return stack.getItem() == item && marker.equals(markerOf(stack));
    }

    /** Returns the stack's marker, or null when it carries none. Never throws on a wrong-typed value. */
    public static String markerOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        Tag tag = data.copyTag().get(MARKER_KEY);
        // Tag.asString() returns Optional and only succeeds for a string-typed value, so a marker of
        // the wrong type reads as "absent" instead of blowing up mid-swing.
        return tag == null ? null : tag.asString().orElse(null);
    }

    public Item item() {
        return item;
    }

    public String marker() {
        return marker;
    }

    @Override
    public String toString() {
        return "ToolMatcher{" + item + " + " + MARKER_KEY + "=" + marker + "}";
    }
}
