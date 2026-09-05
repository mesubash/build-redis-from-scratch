package io.github.mesubash;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// Command line options, in the --name value form redis-server uses.
public class ServerConfig {

    private final Map<String, String> values = new LinkedHashMap<>();

    public ServerConfig(String... args) {
        values.put("port", "6379");
        values.put("dir", ".");
        values.put("dbfilename", "dump.rdb");

        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected an option, got '" + args[i] + "'");
            }
            values.put(args[i].substring(2).toLowerCase(Locale.ROOT), args[i + 1]);
        }
    }

    public int port() {
        return Integer.parseInt(values.get("port"));
    }

    public boolean isReplica() {
        return values.containsKey("replicaof");
    }

    // --replicaof "host port", the form redis-server takes
    public String masterHost() {
        return values.get("replicaof").split("\\s+")[0];
    }

    public int masterPort() {
        return Integer.parseInt(values.get("replicaof").split("\\s+")[1]);
    }

    // null for an unknown parameter, which CONFIG GET reports as an empty result
    public String get(String name) {
        return values.get(name.toLowerCase(Locale.ROOT));
    }
}
