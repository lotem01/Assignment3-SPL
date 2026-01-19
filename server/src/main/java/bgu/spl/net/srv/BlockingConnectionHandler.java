package bgu.spl.net.srv;

import bgu.spl.net.impl.stomp.StompEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;

public class BlockingConnectionHandler<T> implements Runnable, ConnectionHandler<T> {

    private final StompMessagingProtocol<T> protocol;
    private final StompEncoderDecoder encdec;
    private final Socket sock;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private volatile boolean connected = true;

    public BlockingConnectionHandler(Socket sock, StompEncoderDecoder reader, StompMessagingProtocol<T> protocol) {
        this.sock = sock;
        this.encdec = reader;
        this.protocol = protocol;
    }

    public void start(int connectionId, ConnectionsImpl<T> connections) {
        protocol.start(connectionId, connections);
    }

    @Override
    public void run() {
        try (Socket sock = this.sock) { //just for automatic closing
            int read;

            in = new BufferedInputStream(sock.getInputStream());
            out = new BufferedOutputStream(sock.getOutputStream());

            while (!protocol.shouldTerminate() && connected && (read = in.read()) >= 0) {
                String nextMessage = encdec.decodeNextByte((byte) read);
                if (nextMessage != null)    protocol.process(nextMessage);
            }

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    @Override
    public void close() throws IOException {
        connected = false;
        sock.close();
    }

    @Override
    public void send(T msg) {
    try {
        synchronized (this) {
            out.write(encdec.encode((String) msg));
            out.flush();
        }
    } catch (IOException e) {
        connected = false;
        try { close(); } catch (IOException ignore) {  }
    }
        }
}
