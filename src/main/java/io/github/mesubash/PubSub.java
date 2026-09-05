package io.github.mesubash;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Channel registry. Publishers write straight into subscribers' sockets from their own thread.
public class PubSub {

    private final Map<String, Set<ClientSession>> channels = new ConcurrentHashMap<>();

    // returns how many channels this session is now subscribed to
    public int subscribe(ClientSession session, String channel) {
        channels.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(session);
        session.subscriptions().add(channel);
        return session.subscriptions().size();
    }

    public int unsubscribe(ClientSession session, String channel) {
        Set<ClientSession> subscribers = channels.get(channel);
        if (subscribers != null) {
            subscribers.remove(session);
            // don't leave empty channels lying around
            channels.remove(channel, Set.of());
        }
        session.subscriptions().remove(channel);
        return session.subscriptions().size();
    }

    public int publish(String channel, String message) {
        Set<ClientSession> subscribers = channels.get(channel);
        if (subscribers == null) {
            return 0;
        }

        byte[] payload = RespWriter.array(
                RespWriter.bulkString("message"),
                RespWriter.bulkString(channel),
                RespWriter.bulkString(message));

        int delivered = 0;
        for (ClientSession subscriber : subscribers) {
            try {
                subscriber.send(payload);
                delivered++;
            } catch (IOException e) {
                // subscriber went away mid-publish, drop it rather than failing the publish
                subscribers.remove(subscriber);
            }
        }
        return delivered;
    }

    // a disconnecting client must not stay in any channel
    public void removeAll(ClientSession session) {
        for (String channel : session.subscriptions()) {
            Set<ClientSession> subscribers = channels.get(channel);
            if (subscribers != null) {
                subscribers.remove(session);
            }
        }
        session.subscriptions().clear();
    }
}
