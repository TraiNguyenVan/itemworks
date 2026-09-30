package dev.yes.itemworks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads {@code config/itemworks.json} into a list of {@link ToolDefinition}s.
 *
 * <p>Parsing is done by hand rather than by binding the JSON onto fields, for two reasons. An ability's
 * keys are open-ended, so they cannot be fields. And every value in this file is admin-authored, which
 * means a typo is a normal thing to expect, not an exception: each tool is validated on its own and a
 * bad one is dropped with a clear log line, leaving the rest of the file working.
 *
 * <p>On first run the config bundled in the jar is copied out, so the shipped defaults and the shipped
 * documentation cannot drift apart.
 */
public final class ItemworksConfig {

    public static final String FILE_NAME = "itemworks.json";
    public static final Logger LOGGER = LoggerFactory.getLogger("itemworks");

    /** Ticks between full inventory sweeps for the lease. 100 ticks = 5s. */
    public int sweep_interval_ticks = 100;

    public List<ToolDefinition> tools = List.of();

    private static volatile ItemworksConfig instance = new ItemworksConfig();

    public static ItemworksConfig get() {
        return instance;
    }

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            if (Files.exists(file)) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    instance = parse(JsonParser.parseReader(reader), file);
                }
            } else {
                writeBundledDefault(file);
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    instance = parse(JsonParser.parseReader(reader), file);
                }
            }
        } catch (Exception e) {
            // Keep the empty in-memory default: no tools means no abilities fire, which is a safe
            // failure. A half-parsed file is not.
            LOGGER.error("Could not load {}; itemworks abilities are disabled until it is fixed", file, e);
        }
        logSummary();
    }

    private static void writeBundledDefault(Path file) throws IOException {
        try (InputStream bundled = ItemworksConfig.class.getResourceAsStream("/config/" + FILE_NAME)) {
            if (bundled == null) {
                throw new IOException("Bundled default /config/" + FILE_NAME + " is missing from the jar");
            }
            Files.createDirectories(file.getParent());
            Files.copy(bundled, file, StandardCopyOption.REPLACE_EXISTING);
        }
        LOGGER.info("Wrote bundled default config to {}", file);
    }

    private static ItemworksConfig parse(JsonElement root, Path file) {
        ItemworksConfig config = new ItemworksConfig();
        if (root == null || !root.isJsonObject()) {
            LOGGER.error("{} is not a JSON object; no tools were loaded", file);
            return config;
        }
        JsonObject obj = root.getAsJsonObject();

        int sweep = asInt(obj.get("sweep_interval_ticks"), 100);
        config.sweep_interval_ticks = sweep < 20 ? 100 : sweep;

        List<ToolDefinition> tools = new ArrayList<>();
        JsonElement toolsElement = obj.get("tools");
        if (toolsElement != null && toolsElement.isJsonArray()) {
            for (JsonElement entry : toolsElement.getAsJsonArray()) {
                ToolDefinition tool = parseTool(entry);
                if (tool != null) tools.add(tool);
            }
        } else {
            LOGGER.error("{} has no \"tools\" array; no tools were loaded", file);
        }
        config.tools = List.copyOf(tools);
        return config;
    }

    private static ToolDefinition parseTool(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            LOGGER.error("Skipping a tool entry that is not a JSON object");
            return null;
        }
        JsonObject obj = element.getAsJsonObject();

        String id = asString(obj.get("id"));
        if (id == null || id.isBlank()) {
            LOGGER.error("Skipping a tool with no \"id\"");
            return null;
        }

        JsonObject match = asObject(obj.get("match"));
        String itemId = asString(match.get("item"));
        String marker = asString(match.get("marker"));
        if (itemId == null || itemId.isBlank() || marker == null || marker.isBlank()) {
            LOGGER.error("Skipping tool \"{}\": match.item and match.marker are both required", id);
            return null;
        }

        Identifier parsedId;
        try {
            parsedId = Identifier.parse(itemId);
        } catch (RuntimeException e) {
            LOGGER.error("Skipping tool \"{}\": \"{}\" is not a valid item id", id, itemId);
            return null;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(parsedId);
        if (item.isEmpty()) {
            LOGGER.error("Skipping tool \"{}\": no such item \"{}\"", id, itemId);
            return null;
        }

        JsonObject leaseObj = asObject(obj.get("lease"));
        double hours = asDouble(leaseObj.get("hours"), 24.0);
        if (hours <= 0) {
            LOGGER.warn("Tool \"{}\": lease.hours must be positive, using 24.0", id);
            hours = 24.0;
        }
        ToolDefinition.Lease lease = new ToolDefinition.Lease(
                asBoolean(leaseObj.get("enabled"), true),
                hours,
                asBoolean(leaseObj.get("notify_on_stamp"), true),
                asBoolean(leaseObj.get("notify_on_expire"), true));

        Map<String, AbilityConfig> abilities = new LinkedHashMap<>();
        JsonObject abilitiesObj = asObject(obj.get("abilities"));
        for (Map.Entry<String, JsonElement> entry : abilitiesObj.entrySet()) {
            abilities.put(entry.getKey(), AbilityConfig.of(entry.getValue()));
        }

        ToolDefinition tool = new ToolDefinition(id, new ToolMatcher(item.get(), marker), lease, abilities);
        LOGGER.info("Loaded tool \"{}\": {} ({} ability block(s), lease {})",
                id, item.get(), abilities.size(), lease.enabled() ? lease.hours() + "h" : "off");

        for (String abilityId : abilities.keySet()) {
            if (!AbilityRegistry.isKnown(abilityId)) {
                LOGGER.warn("Tool \"{}\" references unknown ability \"{}\"; it will never run. Known: {}",
                        id, abilityId, AbilityRegistry.ids());
            }
        }
        return tool;
    }

    private static void logSummary() {
        ItemworksConfig config = instance;
        if (config.tools.isEmpty()) {
            LOGGER.warn("No tools are configured; {} will not do anything", FILE_NAME);
            return;
        }
        LOGGER.info("itemworks ready: {} tool(s), lease sweep every {} ticks",
                config.tools.size(), config.sweep_interval_ticks);
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String asString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean asBoolean(JsonElement element, boolean fallback) {
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int asInt(JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double asDouble(JsonElement element, double fallback) {
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
