package bgu.spl.net.impl.stomp;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Server;

public class StompServer {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: StompServer <port> <tpc|reactor>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String serverType = args[1];

        if (serverType.equals("tpc")) {
            Server<String> server = Server.threadPerClient(
                    port,
                    () -> (StompMessagingProtocol<String>) new StompProtocol(),
                    () -> new StompEncoderDecoder()
            );
            server.serve();

        } else if (serverType.equals("reactor")) {
            int nThreads = 4;

            Server<String> server = Server.reactor(
                    nThreads,
                    port,
                    () -> (StompMessagingProtocol<String>) new StompProtocol(),
                    () -> new StompEncoderDecoder()
            );
            server.serve();

        } else {
            System.out.println("Unknown server type: " + serverType);
            System.out.println("Usage: StompServer <port> <tpc|reactor>");
        }
    }
}
