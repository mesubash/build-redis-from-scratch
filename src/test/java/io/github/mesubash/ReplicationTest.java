package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicationTest {

    private final RedisStore store = new RedisStore();
    private final CommandDispatcher dispatcher = new CommandDispatcher(store);

    // stands in for a replica's socket
    private final ByteArrayOutputStream replicaStream = new ByteArrayOutputStream();
    private final ClientSession replica = new ClientSession(replicaStream);
    private final ClientSession client = new ClientSession();

    private String reply(ClientSession session, String... command) {
        return new String(dispatcher.execute(command, session), StandardCharsets.UTF_8);
    }

    private String propagated() {
        return replicaStream.toString(StandardCharsets.UTF_8);
    }

    private void registerReplica() {
        reply(replica, "PSYNC", "?", "-1");
        replicaStream.reset();
    }

    @Test
    void replconfIsAcknowledged() {
        assertEquals("+OK\r\n", reply(replica, "REPLCONF", "listening-port", "6380"));
        assertEquals("+OK\r\n", reply(replica, "REPLCONF", "capa", "psync2"));
    }

    @Test
    void psyncRepliesWithFullresyncThenASnapshot() {
        store.set("name", "Subash");
        String result = reply(replica, "PSYNC", "?", "-1");

        assertTrue(result.startsWith("+FULLRESYNC "), result);
        assertTrue(result.contains("REDIS0011"), "the rdb payload should follow the header");

        // framed like a bulk string but with no trailing CRLF after the body
        assertFalse(result.endsWith("\r\n"));
    }

    @Test
    void theSnapshotCarriesTheCurrentKeyspace() throws Exception {
        store.set("name", "Subash");
        byte[] result = dispatcher.execute(new String[]{"PSYNC", "?", "-1"}, replica);

        // skip the +FULLRESYNC line and the $<len> line to reach the snapshot itself
        String text = new String(result, StandardCharsets.UTF_8);
        int rdbStart = text.indexOf("REDIS0011");
        byte[] snapshot = java.util.Arrays.copyOfRange(result, rdbStart, result.length);

        assertEquals("Subash", ReplicaClient.parseSnapshot(snapshot).getFirst().value());
    }

    @Test
    void writesReachRegisteredReplicas() {
        registerReplica();
        reply(client, "SET", "k", "v");

        assertEquals("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n", propagated());
    }

    @Test
    void readsAreNotPropagated() {
        registerReplica();
        reply(client, "SET", "k", "v");
        replicaStream.reset();

        reply(client, "GET", "k");
        reply(client, "EXISTS", "k");
        reply(client, "TYPE", "k");
        assertEquals("", propagated());
    }

    @Test
    void everyWriteFamilyIsPropagated() {
        registerReplica();

        reply(client, "RPUSH", "l", "a");
        reply(client, "HSET", "h", "f", "v");
        reply(client, "SADD", "s", "m");
        reply(client, "ZADD", "z", "1", "m");
        reply(client, "XADD", "x", "1-1", "f", "v");
        reply(client, "INCR", "n");
        reply(client, "EXPIRE", "n", "5");

        String stream = propagated();
        for (String command : new String[]{"RPUSH", "HSET", "SADD", "ZADD", "XADD", "INCR", "EXPIRE"}) {
            assertTrue(stream.contains("$" + command.length() + "\r\n" + command + "\r\n"),
                    command + " was not propagated");
        }
    }

    @Test
    void offsetAdvancesByTheBytesSent() {
        registerReplica();
        long before = dispatcher.replication().offset();

        reply(client, "SET", "k", "v");
        byte[] encoded = Replication.encode(new String[]{"SET", "k", "v"});

        assertEquals(before + encoded.length, dispatcher.replication().offset());
    }

    @Test
    void getackReportsTheReplicasOwnOffset() {
        replica.advanceReplicaOffset(37);
        assertEquals("*3\r\n$8\r\nREPLCONF\r\n$3\r\nACK\r\n$2\r\n37\r\n",
                reply(replica, "REPLCONF", "GETACK", "*"));
    }

    @Test
    void ackFromAReplicaIsNotAnswered() {
        assertEquals("", reply(replica, "REPLCONF", "ACK", "37"));
    }

    @Test
    void waitCountsConnectedReplicas() {
        assertEquals(":0\r\n", reply(client, "WAIT", "0", "100"));
        registerReplica();
        assertEquals(":1\r\n", reply(client, "WAIT", "1", "100"));
    }

    @Test
    void infoReportsRoleAndReplicaCount() {
        registerReplica();
        String info = reply(client, "INFO");

        assertTrue(info.contains("role:master"));
        assertTrue(info.contains("connected_slaves:1"));
        assertTrue(info.contains("master_replid:"));
        assertTrue(info.contains("master_repl_offset:"));
    }

    @Test
    void aReplicaConfiguredServerReportsRoleSlave() {
        CommandDispatcher replicaSide = new CommandDispatcher(
                new RedisStore(), new ServerConfig("--replicaof", "localhost 6379"));

        String info = new String(replicaSide.execute(new String[]{"INFO"}), StandardCharsets.UTF_8);
        assertTrue(info.contains("role:slave"), info);
    }

    @Test
    void replicationIdIsFortyHexCharacters() {
        assertTrue(dispatcher.replication().replicationId().matches("[0-9a-f]{40}"));
    }

    @Test
    void writeCommandsAreRecognised() {
        assertTrue(Replication.isWrite("SET"));
        assertTrue(Replication.isWrite("XADD"));
        assertFalse(Replication.isWrite("GET"));
        assertFalse(Replication.isWrite("PING"));
        assertFalse(Replication.isWrite("SUBSCRIBE"));
    }
}
