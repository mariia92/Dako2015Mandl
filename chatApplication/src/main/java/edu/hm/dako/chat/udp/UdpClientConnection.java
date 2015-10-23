package edu.hm.dako.chat.udp;

import edu.hm.dako.chat.connection.Connection;

import java.io.IOException;
import java.io.Serializable;

public class UdpClientConnection implements Connection {

	// UDP-Socket der Verbindung
    private UdpSocket clientSocket;

    /**
     * Timeout fuer {@link UdpSocket#receive(int)}
     * Das ist die maximale Wartezeit beim Empfang von UDP-Datagrammen
     */
    
    private final int receivingTimeout;  
   
    public UdpClientConnection(UdpSocket clientSocket, int receivingTimeout) throws Exception {
        this.clientSocket = clientSocket;
        this.receivingTimeout = receivingTimeout;
    }

    @Override
    public Serializable receive(int timeout) throws Exception {
        return (Serializable) clientSocket.receive(timeout);
    }

    @Override
    public Serializable receive() throws Exception {
        return (Serializable) clientSocket.receive(receivingTimeout);
    }
    @Override
    public void send(Serializable message) throws Exception {
        clientSocket.send(clientSocket.getRemoteAddress(), clientSocket.getRemotePort(), message);
    }

    @Override
    public void close() throws IOException {
        clientSocket.close();
    }
}
