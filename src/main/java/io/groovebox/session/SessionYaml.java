package io.groovebox.session;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** YAML maps and lists for session files, translated to the same fields the legacy reader validates. */
final class SessionYaml {
    private SessionYaml() {}

    static boolean accepts(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    static Properties read(Path file) throws IOException {
        var settings = LoadSettings.builder().setLabel(file.toString()).setAllowDuplicateKeys(false)
                .setMaxAliasesForCollections(0).setCodePointLimit(16 * 1024 * 1024).build();
        try (var input = Files.newInputStream(file)) {
            Object document = new Load(settings).loadFromInputStream(input);
            if (!(document instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected a YAML mapping");
            Properties fields = new Properties();
            flatten(document, "", fields);
            return fields;
        } catch (RuntimeException failure) {
            throw new IOException("Cannot read YAML " + file.getFileName() + ": " + failure.getMessage(), failure);
        }
    }

    static void write(Writer writer, Map<String, Object> fields) throws IOException {
        Map<String, Object> tree = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            String[] parts = key.split("\\.");
            Map<String, Object> node = tree;
            for (int i = 0; i < parts.length - 1; i++) {
                node = mapping(node.computeIfAbsent(parts[i], unused -> new LinkedHashMap<>()));
            }
            node.put(parts[parts.length - 1], value);
        });
        compact(tree);
        var settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK)
                .setIndent(2).setIndicatorIndent(2).setIndentWithIndicator(true).build();
        writer.write(new Dump(settings).dumpToString(tree));
    }

    private static void compact(Map<String, Object> node) {
        for (Object value : node.values()) if (value instanceof Map<?, ?>) compact(mapping(value));
        collapse(node, "tracks", "track", true);
        collapse(node, "rows", "row", false);
        collapse(node, "notes", "note", false);
        if (node.get("steps") instanceof Number steps) {
            node.remove("steps");
            node.put("accents", elements(node.remove("step"), steps.intValue()));
        }
        if (node.get("starts") instanceof String starts && node.get("order") instanceof String order) {
            node.put("starts", integers(starts)); node.put("order", integers(order));
        }
    }

    private static void collapse(Map<String, Object> node, String plural, String singular, boolean metadata) {
        if (!(node.get(plural) instanceof Map<?, ?>)) return;
        Map<String, Object> group = mapping(node.get(plural));
        if (!(group.get("count") instanceof Number count)) return;
        List<Object> items = elements(node.remove(singular), count.intValue());
        group.remove("count");
        if (metadata) group.put("items", items); else node.put(plural, items);
    }

    private static List<Object> elements(Object numbered, int count) {
        var entries = numbered == null ? Map.<String, Object>of() : mapping(numbered);
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(entries.get(Integer.toString(i)));
        return list;
    }

    private static List<Integer> integers(String value) {
        // Distinct lists keep the emitter from introducing aliases for shared empty lists.
        return value.isEmpty() ? new ArrayList<>() : java.util.Arrays.stream(value.split(",")).map(Integer::valueOf).toList();
    }

    private static void flatten(Object value, String path, Properties fields) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isEmpty() || key.contains(".")) {
                    throw new IllegalArgumentException("Invalid YAML key at " + path);
                }
                flatten(entry.getValue(), path.isEmpty() ? key : path + "." + key, fields);
            }
        } else if (value instanceof List<?> list) {
            String prefix, count;
            if (path.equals("tracks.items")) { prefix = "track"; count = "tracks.count"; }
            else if (path.equals("rows")) { prefix = "row"; count = "rows.count"; }
            else if (path.matches("track\\.\\d+\\.notes")) {
                prefix = path.substring(0, path.length() - 5) + "note"; count = path + ".count";
            } else if (path.matches("row\\.\\d+\\.accents")) {
                String row = path.substring(0, path.length() - 7);
                prefix = row + "step"; count = row + "steps";
            } else if (path.matches("track\\.\\d+\\.slice\\.(starts|order)")) {
                List<String> numbers = new ArrayList<>();
                for (Object item : list) {
                    if (!(item instanceof Number number) || number.doubleValue() != number.intValue()) {
                        throw new IllegalArgumentException("Expected integer slice indices at " + path);
                    }
                    numbers.add(Integer.toString(number.intValue()));
                }
                fields.setProperty(path, String.join(",", numbers)); return;
            } else throw new IllegalArgumentException("Unexpected YAML list at " + path);
            fields.setProperty(count, Integer.toString(list.size()));
            for (int i = 0; i < list.size(); i++) flatten(list.get(i), prefix + "." + i, fields);
        } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            if (fields.putIfAbsent(path, value.toString()) != null) throw new IllegalArgumentException("Duplicate setting " + path);
        } else throw new IllegalArgumentException("Expected a setting at " + path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        // Only maps constructed above are cast; loaded YAML is checked key by key in flatten.
        return (Map<String, Object>) value;
    }
}
