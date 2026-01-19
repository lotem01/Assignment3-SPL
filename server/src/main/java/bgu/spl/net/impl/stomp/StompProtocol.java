package bgu.spl.net.impl.stomp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.ConnectionsImpl;
import bgu.spl.net.impl.data.Database;
import bgu.spl.net.impl.data.LoginStatus;


public class StompProtocol implements StompMessagingProtocol<String> {
    private int connectionId;
    private ConnectionsImpl<String> connections;
    private boolean terminate = false;
    private final Map<String, String> subIdToChannel = new HashMap<>();
    private final Map<String, String> channelToSubId = new HashMap<>();
    private boolean loggedIn = false;
    private String username = null;
    private String passcode = null;
    private final Database database = Database.getInstance();
    private final Set<String> reportedFiles = new HashSet<>();

    private static final AtomicInteger msgId = new AtomicInteger(0);

    @Override
    public void start(int connectionId, ConnectionsImpl<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
        this.terminate = false;
        this.subIdToChannel.clear();
        this.channelToSubId.clear();
        this.reportedFiles.clear();
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
        String login = null;
        String pass = null;
        for (String line : messageArr) {
            if (line.startsWith("login:")) {
                login = line.substring(6);
            } else if (line.startsWith("passcode:")) {
                pass = line.substring(9);
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
        if (login == null || login.isEmpty() || pass == null) {
            sendError("Missing login/passcode", receipt);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        LoginStatus status = database.login(connectionId, login, pass);
        if (status == LoginStatus.ALREADY_LOGGED_IN) {
            sendError("User already logged in", receipt);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        if (status == LoginStatus.WRONG_PASSWORD) {
            sendError("Wrong password", receipt);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        if (status == LoginStatus.CLIENT_ALREADY_CONNECTED) {
            sendError("Client already connected", receipt);
            connections.disconnect(connectionId);
            terminate = true;
            return;
        }
        this.username = login;
        this.passcode = pass;
        this.loggedIn = true;
        connections.send(connectionId, "CONNECTED\nversion:1.2\n\n");
        if (receipt != null)
            sendReceipt(receipt);

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
        String receipt = null;
        for (String line : messageArr) {
            if (line.startsWith("destination:")) {
                destination = line.substring(12);
            } else if (line.startsWith("id:")) {
                id = line.substring(3);
            } else if (line.startsWith("receipt:")) {
                receipt = line.substring(8);
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
        if (receipt != null)
            sendReceipt(receipt);
    }

    public void processUnsubscribe(String[] messageArr) {
        if (!loggedIn) {
            sendError("Not logged in", null);
            connections.disconnect(connectionId);
            return;
        }
        String id = null;
        String receipt = null;
        for (String line : messageArr) {
            if (line.startsWith("id:")) {
                id = line.substring(3);
            } else if (line.startsWith("receipt:")) {
                receipt = line.substring(8);
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
        if (receipt != null)
            sendReceipt(receipt);
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
        String file = null;
        String body = "";
        boolean bodyStarted = false;

        for (String line : messageArr) {
            if (line.startsWith("destination:")) {
                destination = line.substring(12);
            } else if (line.startsWith("receipt:")){
                receipt = line.substring(8);
            } else if (line.startsWith("file:")) {
                file = line.substring(5);
            } else if (line.isEmpty()) {
                if (bodyStarted) {
                    body += "\n";
                } else {
                    bodyStarted = true;
                }
            } else if (bodyStarted) {
                body += line + "\n";
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
        if (file != null) {
            String fileKey = username + "\n" + destination + "\n" + file;
            if (reportedFiles.add(fileKey)) {
                database.trackFileUpload(username, file, destination);
            }
        }
        if (receipt != null)
            sendReceipt(receipt);
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
        database.logout(connectionId);
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

    private void sendReceipt(String receiptId) {
        String receiptMessage = "RECEIPT\nreceipt-id:" + receiptId + "\n\n";
        connections.send(connectionId, receiptMessage);
    }

    @Override
    public boolean shouldTerminate() {
        return terminate;
    }
}
