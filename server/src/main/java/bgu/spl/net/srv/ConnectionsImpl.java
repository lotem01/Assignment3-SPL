package bgu.spl.net.srv;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl<T> implements Connections<T> {

    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> handlers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, String>> channelSubs = new ConcurrentHashMap<>();



    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        handlers.put(connectionId, handler);
    }

    public void subscribe(int connectionId, String channel, String subId) {
        if(!channelSubs.keySet().contains(channel)) {
            channelSubs.put(channel, new ConcurrentHashMap<>());
        }
        channelSubs.get(channel).put(connectionId, subId);
    }

    public void unsubscribe(int connectionId, String channel) {
        ConcurrentHashMap<Integer, String> subs = channelSubs.get(channel);
        if (subs != null) {
            subs.remove(connectionId);
            if (subs.isEmpty()) channelSubs.remove(channel, subs);
        }
    }

    public boolean isSubscribed(int connectionId, String channel) {
        ConcurrentHashMap<Integer, String> subs = channelSubs.get(channel);
        return subs != null && subs.containsKey(connectionId);
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
        Map<Integer, String> subs = channelSubs.get(channel);
        if (subs == null) return;
        for (Integer id : subs.keySet()) send(id, msg);
    }


    @Override
    public void disconnect(int connectionId) {
        ConnectionHandler<T> h = handlers.remove(connectionId);
        for (ConcurrentHashMap<Integer, String> subs : channelSubs.values()) {
            subs.remove(connectionId);
        }

        if (h != null) {
            try {
                if (h instanceof NonBlockingConnectionHandler) {
                    return;
                }
                h.close();
            } catch (IOException ignored) {}
        }
    }

    public Map<Integer, String> getSubscribers(String channel) {
        return channelSubs.get(channel);
    }
}
