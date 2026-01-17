package bgu.spl.net.impl.stomp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.ConnectionsImpl;


//RECEIPT ID NEEDED TO BE ADDED - WHEN WORKING ON CLIENT IMPLEMENTATION
public class StompProtocol implements StompMessagingProtocol<String> {
    private int connectionId;
    private ConnectionsImpl<String> connections;
    private boolean terminate = false;
    private final Map<String, String> subIdToChannel = new HashMap<>();
    private final Map<String, String> channelToSubId = new HashMap<>();
    private boolean loggedIn = false;
    private String username = null;
    private String passcode = null;

    private static final AtomicInteger msgId = new AtomicInteger(0);

    @Override
    public void start(int connectionId, ConnectionsImpl<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
        this.terminate = false;
        this.subIdToChannel.clear();
        this.channelToSubId.clear();
        this.loggedIn = false;
        this.username = null;
        this.passcode = null;
    }

    @Override
    public void process(String message) {
        String[] messageArr = message.split("\n");
        String command = messageArr[0];
        switch (command) {
            case "CONNECT":
                processConnect(messageArr);
                break;
            case "SUBSCRIBE":
                processSubscribe(messageArr);
                break;
            case "UNSUBSCRIBE":
                processUnsubscribe(messageArr);
                break;
            case "SEND":
                processSend(messageArr);
                break;
            case "DISCONNECT":
                processDisconnect(messageArr);
                break;
            default: {
                String receiptId = null;
                for (String line : messageArr) {
                    if (line.startsWith("receipt:")) receiptId = line.substring(8);
                }
                sendError("Unknown command", receiptId);
                connections.disconnect(connectionId);
                terminate = true;
                break;
            }
        }
    }

    public void processConnect(String[] messageArr) {
        if (loggedIn) {
            sendError("Already logged in", null);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        String accept = null;
        String receipt = null;
        for (String line : messageArr) {
            if (line.startsWith("login:")) {
                this.username = line.substring(6);
            } else if (line.startsWith("passcode:")) {
                this.passcode = line.substring(9);
            } else if (line.startsWith("accept-version:")) {
                accept = line.substring(15);
            } else if (line.startsWith("receipt:")){
                receipt = line.substring(8);
            }
        }
        if (accept == null || !accept.contains("1.2")) {
            sendError("Unsupported STOMP version (need 1.2)", receipt);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        if (this.username == null || this.username.isEmpty() || this.passcode == null) {
            sendError("Missing login/passcode", receipt);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        this.loggedIn = true;
        String response = "";
        if(receipt != null)
            response = "CONNECTED\nversion:1.2\nreceipt:" + receipt + "\n\n";
        else
            response = "CONNECTED\nversion:1.2\n\n";
        connections.send(connectionId, response);

    }

    public void processSubscribe(String[] messageArr) {
        if (!loggedIn) {
            sendError("Not logged in", null);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        String destination = null;
        String id = null;
        for (String line : messageArr) {
            if (line.startsWith("destination:")) {
                destination = line.substring(12);
            } else if (line.startsWith("id:")) {
                id = line.substring(3);
            }
        }
        if (destination == null || id == null) {
            sendError("Missing destination or id", null);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        if (channelToSubId.containsKey(destination)) {
            sendError("Already subscribed to destination", null);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }

        if (subIdToChannel.containsKey(id)) {
            sendError("Subscription id already used", null);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        subIdToChannel.put(id, destination);
        channelToSubId.put(destination, id);
        connections.subscribe(connectionId, destination, id);
    }

    public void processUnsubscribe(String[] messageArr) {
        if (!loggedIn) {
            sendError("Not logged in", null);
            connections.disconnect(connectionId);
            return;
        }
        String id = null;
        for (String line : messageArr) {
            if (line.startsWith("id:")) {
                id = line.substring(3);
            }
        }
        if (id == null) {
            sendError("Missing id", null);
            connections.disconnect(connectionId);
            return;
        }
        if (!subIdToChannel.containsKey(id)) {
            sendError("Subscription id not found", null);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        String destination = subIdToChannel.get(id);
        connections.unsubscribe(connectionId, destination);
        subIdToChannel.remove(id);
        channelToSubId.remove(destination);
    }

    public void processSend(String[] messageArr) {
        if (!loggedIn) {
            sendError("Not logged in", null);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        String receipt = null;
        String destination = null;
        String body = "";
        boolean bodyStarted = false;

        for (String line : messageArr) {
            if (line.startsWith("destination:")) {
                destination = line.substring(12);
            } else if (line.isEmpty()) {
                bodyStarted = true;
            } else if (bodyStarted) {
                body += line + "\n";
            }
            else if (line.startsWith("receipt:")){
                receipt = line.substring(8);
            }
        }
        if (destination == null) {
            sendError("Missing destination", receipt);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        if (body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }

        Map<Integer, String> subs = connections.getSubscribers(destination);
        if (subs == null)
            return;

        if (!connections.isSubscribed(connectionId, destination)) {
            sendError("Not subscribed to destination", receipt);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        for (Map.Entry<Integer, String> e : subs.entrySet()) {
            int id = e.getKey();
            String subId = e.getValue();

            String messageToSend = "MESSAGE\ndestination:" + destination +
                    "\nsubscription:" + subId +
                    "\nmessage-id:" + msgId.get() +
                    "\n\n" + body;

            connections.send(id, messageToSend);
        }
        msgId.incrementAndGet();
    }

    public void processDisconnect(String[] messageArr) {
        if (!loggedIn) {
            sendError("Not logged in", null);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        String receiptId = null;
        for (String line : messageArr) {
            if (line.startsWith("receipt:")) {
                receiptId = line.substring(8);
            }
        }
        if (receiptId != null) {
            String receiptMessage = "RECEIPT\nreceipt-id:" + receiptId + "\n\n";
            connections.send(connectionId, receiptMessage);
        } else {
            sendError("Missing receipt id", receiptId);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        for (String channel : channelToSubId.keySet()) {
            connections.unsubscribe(connectionId, channel);
        }
        subIdToChannel.clear();
        channelToSubId.clear();
        terminate = true;
        connections.disconnect(connectionId);
    }

    public void sendError(String errorMessage, String receiptId) { // SEND RECEIPT ID??????
        if(receiptId != null) {
            String errorResponse = "ERROR\nmessage:" + errorMessage + "\nreceipt-id:" + receiptId + "\n\n";
            connections.send(connectionId, errorResponse);
        } else {
            String errorResponse = "ERROR\nmessage:" + errorMessage + "\n\n";
            connections.send(connectionId, errorResponse);
        }
    }

    @Override
    public boolean shouldTerminate() {
        return terminate;
    }
}