package de.hytalehub.service;

import de.hytalehub.HytaleHubPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Safe JSON persistence for the plugin's data files.
 *
 * - Writes go to a temp file first and are then moved over the target, so a crash mid-write
 *   never leaves a half-written file behind.
 * - A file that cannot be parsed is renamed to "<name>.corrupt-<timestamp>" and loading starts
 *   empty. The plugin keeps running and the broken data stays available for manual recovery.
 */
public final class JsonFiles {

    private JsonFiles() {
    }

    public static void writeAtomic(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads and parses a file. Returns null if the file does not exist or was corrupt
     * (in that case it is moved aside and an error is logged).
     */
    public static <T> T read(Path path, Function<String, T> parser) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        try {
            return parser.apply(json);
        } catch (RuntimeException e) {
            Path aside = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
            HytaleHubPlugin plugin = HytaleHubPlugin.getInstance();
            if (plugin != null) {
                plugin.log(Level.SEVERE, "Could not parse " + path.getFileName() + " (" + e.getMessage()
                    + "). Moved it to " + aside.getFileName() + " and started empty.");
            }
            return null;
        }
    }
}
