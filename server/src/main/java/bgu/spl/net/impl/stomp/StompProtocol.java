package bgu.spl.net.impl.stomp;

import java.util.HashMap;
import java.util.Map;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import bgu.spl.net.srv.ConnectionsImpl;

public class StompProtocol implements StompMessagingProtocol<String> {
    private int connectionId;
    private Connections<String> connections;
    private boolean terminate = false;
    private final Map<String, String> subIdToChannel = new HashMap<>();
    private final Map<String, String> channelToSubId = new HashMap<>();
    private boolean loggedIn = false;
    private String username = null;
    private String passcode = null;

    @Override
    public void start(int connectionId, Connections<String> connections) {
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
        // IMPLEMENT IF NEEDED
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
            default:
                sendError("Unknown command");
                connections.disconnect(connectionId);
                break;
        }
    }

    public void processConnect(String[] messageArr) {
        if (loggedIn) {
            sendError("Already logged in");
            connections.disconnect(connectionId);
            return;
        }
        String accept = null;
        for (String line : messageArr) {
            if (line.startsWith("login:")) {
                this.username = line.substring(6);
            } else if (line.startsWith("passcode:")) {
                this.passcode = line.substring(9);
            } else if (line.startsWith("accept-version:")) {
                accept = line.substring(15);
            }
        }
        if (accept == null || !accept.contains("1.2")) {
            sendError("Unsupported STOMP version (need 1.2)");
            connections.disconnect(connectionId);
            return;
        }
        if (this.username == null || this.username.isEmpty() || this.passcode == null) {
            sendError("Missing login/passcode");
            connections.disconnect(connectionId);
            return;
        }
        this.loggedIn = true;
        String response = "CONNECTED\nversion:1.2\n\n\u0000";
        connections.send(connectionId, response);

    }

    public void processSubscribe(String[] messageArr) {
        // IMPLEMENT IF NEEDED
    }

    public void processUnsubscribe(String[] messageArr) {
        // IMPLEMENT IF NEEDED
    }

    public void processSend(String[] messageArr) {
        // IMPLEMENT IF NEEDED
    }

    public void processDisconnect(String[] messageArr) {
        // IMPLEMENT IF NEEDED
    }

    public void sendError(String errorMessage) {
        // IMPLEMENT IF NEEDED

    }

    @Override
    public boolean shouldTerminate() {
        // IMPLEMENT IF NEEDED
        return false;
    }
}