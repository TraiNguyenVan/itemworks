package dev.yes.itemworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One ability's config block, kept as raw JSON so that every ability can define its own keys without
 * the core knowing anything about them.
 *
 * <p>Values that are expensive or fallible to derive — parsed block tags, offset arrays — are compiled
 * once and memoised. A config instance is built at load time and then read on every block break, so
 * re-parsing per swing would be waste.
 */
public final class AbilityConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("itemworks");

    private final JsonObject json;
    private final Map<String, Object> compiled = new ConcurrentHashMap<>();

    private AbilityConfig(JsonObject json) {
        this.json = json;
    }

    public static AbilityConfig of(JsonElement element) {
        return new AbilityConfig(element != null && element.isJsonObject()
                ? element.getAsJsonObject()
                : new JsonObject());
    }

    /** Abilities are opt-out by default: omitting {@code enabled} means the ability runs. */
    public boolean enabled() {
        return flag("enabled", true);
    }

    public boolean flag(String key, boolean fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public String string(String key, String fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public double number(String key, double fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public List<String> strings(String key) {
        JsonElement element = json.get(key);
        List<String> out = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return out;
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) out.add(item.getAsString());
        }
        return out;
    }

    /** Compiled once per config instance. Empty when the key is absent or unusable. */
    public BlockFilter blockFilter() {
        return cached("blockFilter", () -> BlockFilter.parse(strings("blocks")));
    }

    /** Compiled once per config instance. Each entry is a {@code [dx, dy, dz]} triple. */
    public int[][] offsets() {
        return cached("offsets", this::parseOffsets);
    }

    private int[][] parseOffsets() {
        JsonElement element = json.get("offsets");
        if (element == null || !element.isJsonArray()) return new int[0][];

        JsonArray array = element.getAsJsonArray();
        List<int[]> parsed = new ArrayList<>(array.size());
        for (JsonElement entry : array) {
            if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 3) {
                LOGGER.warn("Ignoring offset {}: expected three integers [dx,dy,dz]", entry);
                continue;
            }
            JsonArray triple = entry.getAsJsonArray();
            try {
                parsed.add(new int[]{
                        triple.get(0).getAsInt(),
                        triple.get(1).getAsInt(),
                        triple.get(2).getAsInt()
                });
            } catch (RuntimeException e) {
                LOGGER.warn("Ignoring offset {}: not three integers", entry);
            }
        }
        return parsed.toArray(new int[0][]);
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> supplier) {
        return (T) compiled.computeIfAbsent(key, ignored -> supplier.get());
    }

    @Override
    public String toString() {
        return json.toString();
    }
}
