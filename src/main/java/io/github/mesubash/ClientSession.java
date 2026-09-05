package io.github.mesubash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Per-connection state. Everything else in the server is shared, this is not.
public class ClientSession {

    private boolean inTransaction;
    private boolean aborted;
    private final List<String[]> queued = new ArrayList<>();

    private final Set<String> subscriptions = new LinkedHashSet<>();

    // key to the version it had when WATCH was called
    private final Map<String, Long> watched = new LinkedHashMap<>();
    private final OutputStream out;

    public ClientSession(OutputStream out) {
        this.out = out;
    }

    // tests and anything that never pushes to the client
    public ClientSession() {
        this(new ByteArrayOutputStream());
    }

    public Set<String> subscriptions() {
        return subscriptions;
    }

    public boolean isSubscribed() {
        return !subscriptions.isEmpty();
    }

    // publishers write here from their own threads, so one writer at a time
    public synchronized void send(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    public boolean inTransaction() {
        return inTransaction;
    }

    public void begin() {
        inTransaction = true;
        aborted = false;
        queued.clear();
    }

    public void queue(String[] command) {
        queued.add(command);
    }

    // a command that failed to queue poisons the whole transaction
    public void abort() {
        aborted = true;
    }

    public boolean aborted() {
        return aborted;
    }

    public List<String[]> drain() {
        List<String[]> commands = new ArrayList<>(queued);
        reset();
        return commands;
    }

    public Map<String, Long> watched() {
        return watched;
    }

    // EXEC and DISCARD both end the watch, whether or not they ran anything
    public void reset() {
        inTransaction = false;
        aborted = false;
        queued.clear();
        watched.clear();
    }
}
