package puregero.multipaper.starter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads the supported Purpur versions from {@code purpur-versions.properties}.
 * The keys are the same ones the Gradle build uses
 * ({@code <version>.mcVersion}, {@code .branch}, {@code .ref}, {@code .channel}),
 * so the starter and the patcher always agree on what a version means.
 */
final class VersionRegistry {
    static final class Entry {
        final String id;
        final String mcVersion;
        final String apiVersion;
        final String branch;
        final String ref;
        final String channel;

        Entry(String id, String mcVersion, String apiVersion, String branch, String ref, String channel) {
            this.id = id;
            this.mcVersion = mcVersion;
            this.apiVersion = apiVersion;
            this.branch = branch;
            this.ref = ref;
            this.channel = channel;
        }
    }

    private final List<String> order;
    private final Map<String, Entry> entries;

    private VersionRegistry(List<String> order, Map<String, Entry> entries) {
        this.order = order;
        this.entries = entries;
    }

    static VersionRegistry load(Path file) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        String list = props.getProperty("purpur.version.list");
        if (list == null || list.isBlank()) {
            throw new StarterException("purpur.version.list is missing from " + file);
        }

        List<String> order = new ArrayList<>();
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String raw : list.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) {
                continue;
            }
            order.add(id);
            entries.put(id, new Entry(
                    id,
                    props.getProperty(id + ".mcVersion", id),
                    props.getProperty(id + ".apiVersion", id),
                    props.getProperty(id + ".branch", ""),
                    props.getProperty(id + ".ref", ""),
                    props.getProperty(id + ".channel", "")
            ));
        }
        if (order.isEmpty()) {
            throw new StarterException("purpur.version.list in " + file + " does not contain any versions");
        }
        return new VersionRegistry(order, entries);
    }

    List<String> ids() {
        return order;
    }

    boolean contains(String id) {
        return entries.containsKey(id);
    }

    Entry get(String id) {
        return entries.get(id);
    }

    String availableList() {
        return String.join(", ", order);
    }
}
