package edu.hm.dako.chat.udp;

import edu.hm.dako.chat.connection.Connection;

import java.io.IOException;
import java.io.Serializable;

public class UdpServerConnection implements Connection {

    private UdpSocket serverSocket;

    private UdpRemoteObject udpRemoteObject; // Empfangene Request-PDU
       
    public UdpServerConnection(UdpSocket serverSocket) throws Exception {
        this.serverSocket = serverSocket;

        // Empfangende Nutz- und Adressdaten für die Verarbeitung zwischenspeichern
        Object pdu = serverSocket.receive(0);
        udpRemoteObject = new UdpRemoteObject(serverSocket.getRemoteAddress(),serverSocket.getRemotePort(), pdu);
    }

    /*
     * Der Empfang der Daten vom UDP-Client erfolgt bereits im Konstruktor.
     * Diese Methode gibt nur die bereits empfangene Nachricht zurueck.
     * @see edu.hm.dako.echo.connection.Connection#receive()
     */
    @Override
    public Serializable receive(int timeout) throws Exception {
    	//Test
    	System.out.println("Echo-Request empfangen von " 
    			+ serverSocket.getRemoteAddress() +":" 
    			+ serverSocket.getRemotePort() + " ueber lokalen Port "
    			+ serverSocket.getLocalPort());

        return (Serializable) udpRemoteObject.getObject();
    }

    public Serializable receive() throws Exception {
        return (Serializable) udpRemoteObject.getObject();
    }

    @Override
    public void send(Serializable message) throws Exception {
        serverSocket.send(udpRemoteObject.getRemoteAddress(), udpRemoteObject.getRemotePort(), message);
    }

    @Override
    /* Dies ist nur eine Dummy-Methode.
	 * Der ServerSocket darf nicht geschlossen werden, da der Server sonst keine 
	 * Echo-Requests mehr entgegennehmen kann. Es gibt im Unterschied zu TCP-Sockets keine 
	 * Verbindungssockets bei UDP, sondern nur ein UDP-Socket, ueber das alles empfangen wird.
	 */ 
    public void close() throws IOException {       
    }
}
