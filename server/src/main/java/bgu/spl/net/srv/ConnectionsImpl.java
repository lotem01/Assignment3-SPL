package bgu.spl.net.srv;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl<T> implements Connections<T> {

    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> handlers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Set<Integer>> channelSubs = new ConcurrentHashMap<>();

    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        handlers.put(connectionId, handler);
    }

    public void subscribe(int connectionId, String channel) {
        if(!channelSubs.containsKey(channel)) {
            channelSubs.put(channel, ConcurrentHashMap.newKeySet());
        }
        channelSubs.get(channel).add(connectionId);
    }

    public void unsubscribe(int connectionId, String channel) {
        Set<Integer> subs = channelSubs.get(channel);
        if (subs != null) {
            subs.remove(connectionId);
            if (subs.isEmpty()) channelSubs.remove(channel, subs);
        }
    }

    public boolean isSubscribed(int connectionId, String channel) {
        Set<Integer> subs = channelSubs.get(channel);
        return subs != null && subs.contains(connectionId);
    }

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> h = handlers.get(connectionId);
        if (h == null) return false;
        try {
            h.send(msg);
            return true;
        } catch (Exception e) {
            disconnect(connectionId);
            return false;
        }
    }

    @Override
    public void send(String channel, T msg) {
        Set<Integer> subs = channelSubs.get(channel);
        if (subs == null) return;
        for (Integer id : subs) {
            send(id, msg);
        }
    }

    @Override
    public void disconnect(int connectionId) {
        ConnectionHandler<T> h = handlers.remove(connectionId);
        for (Set<Integer> subs : channelSubs.values()) {
            subs.remove(connectionId);
        }

        if (h != null) {
            try {
                h.close();
            } catch (IOException ignored) {}
        }
    }
}
