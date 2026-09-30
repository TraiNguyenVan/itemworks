package dev.yes.itemworks;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A resolved set of blocks, built from config strings that are either block tags or single block ids.
 *
 * <p>Tags are the reason this works without a rebuild: {@code #minecraft:mineable/shovel} is defined by
 * the game itself, so a config change picks up new blocks automatically. Single ids are accepted too,
 * for pinning an ability to a hand-written list.
 *
 * <p>Unparseable ids are reported and skipped rather than aborting the load, so one typo costs the
 * affected entries instead of every tool in the file.
 */
public final class BlockFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger("itemworks");

    private final List<TagKey<Block>> tags;
    private final Set<Block> blocks;

    private BlockFilter(List<TagKey<Block>> tags, Set<Block> blocks) {
        this.tags = tags;
        this.blocks = blocks;
    }

    public static BlockFilter parse(List<String> ids) {
        List<TagKey<Block>> tags = new ArrayList<>();
        Set<Block> blocks = new java.util.LinkedHashSet<>();

        for (String raw : ids) {
            String id = raw.trim();
            if (id.isEmpty()) continue;
            boolean isTag = id.charAt(0) == '#';
            String path = isTag ? id.substring(1) : id;

            Identifier parsed;
            try {
                parsed = Identifier.parse(path);
            } catch (RuntimeException e) {
                LOGGER.error("Ignoring block entry \"{}\": not a valid identifier", raw);
                continue;
            }

            if (isTag) {
                tags.add(TagKey.create(Registries.BLOCK, parsed));
            } else {
                // getOptional, not getValue: the block registry is defaulted, so getValue would
                // silently hand back air for a typo instead of telling us about it.
                BuiltInRegistries.BLOCK.getOptional(parsed).ifPresentOrElse(
                        blocks::add,
                        () -> LOGGER.error("Ignoring block entry \"{}\": no such block", raw));
            }
        }
        return new BlockFilter(List.copyOf(tags), Set.copyOf(blocks));
    }

    public boolean matches(BlockState state) {
        if (blocks.contains(state.getBlock())) return true;
        for (TagKey<Block> tag : tags) {
            // 26.3 has no single-argument is(TagKey) overload; the predicate variant is the only one.
            if (state.is(tag, candidate -> true)) return true;
        }
        return false;
    }

    /** True when nothing resolved, which almost always means the config key was missing or mistyped. */
    public boolean isEmpty() {
        return tags.isEmpty() && blocks.isEmpty();
    }

    @Override
    public String toString() {
        return "BlockFilter{tags=" + tags + ", blocks=" + blocks.size() + "}";
    }
}
