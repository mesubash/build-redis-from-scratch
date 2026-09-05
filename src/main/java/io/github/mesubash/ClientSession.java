package io.github.mesubash;

import java.util.ArrayList;
import java.util.List;

// Per-connection state. Everything else in the server is shared, this is not.
public class ClientSession {

    private boolean inTransaction;
    private boolean aborted;
    private final List<String[]> queued = new ArrayList<>();

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

    public void reset() {
        inTransaction = false;
        aborted = false;
        queued.clear();
    }
}
