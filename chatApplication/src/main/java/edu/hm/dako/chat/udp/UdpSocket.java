package edu.hm.dako.chat.udp;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Klasse UdpSocket
 * <p/>
 * Diese Klasse kapselt die Datagram-Sockets und stellt
 * eine etwas komfortablere Schnittstelle zur Verfuegung.
 * <p/>
 * Der Mehrwert dieser Klasse im Vergleich zur Standard-DatagramSocket-Klasse
 * ist die Nutzung eines Objektstroms zur Kommunikation ueber UDP.
 * <p/>
 * Achtung: Maximale Datagramlaenge: 64 KByte
 *
 * @author Mandl
 * @version 1.0.0
 */
public class UdpSocket {
    private static Log log = LogFactory.getLog(UdpSocket.class);
    private DatagramSocket socket;
    private InetAddress remoteAddress;
    private int remotePort;

       /**
     * Konstruktor
     *
     * @param port UDP-Port, der lokal fuer das Datagramm-Socket verwendet werden soll
     */
    public UdpSocket(int port) throws SocketException {
      
        try {
        	socket = new DatagramSocket(port);
        	System.out.println("Groesse des Empfangspuffers des Datagram-Sockets: " + socket.getReceiveBufferSize() + " Byte");
        	System.out.println("Groesse des Sendepuffers des Datagram-Sockets: " + socket.getSendBufferSize() + " Byte");   
    	} catch (BindException e) {
    		log.error("Port " + port +" auf dem Rechner schon in Benutzung, Bind Exception: "+ e);
    		System.out.println("Port " + port +" auf dem Rechner schon in Benutzung, Bind Exception: "+ e);
    		throw e;
    	} catch (SocketException e) {
    		log.error("Datagram-Socketfehler: " + e);
    		throw e;
    	}
    }
    /**
     * Konstruktor
     *
     * @param port              UDP-Port, der lokal fuer das Datagramm-Socket verwendet werden soll
     * @param sendBufferSize    Groesse des Sendepuffers in Byte
     * @param receiveBufferSize Groesse des Empfangspuffers in Byte
     */

    public UdpSocket(int port, int sendBufferSize, int receiveBufferSize) throws SocketException {
      
        try {
         	socket = new DatagramSocket(port);
            socket.setReceiveBufferSize(receiveBufferSize);
            socket.setSendBufferSize(sendBufferSize);
            System.out.println("Groesse des Empfangspuffers des Datagram-Sockets: " + socket.getReceiveBufferSize() + " Byte");
            System.out.println("Groesse des Sendepuffers des Datagram-Sockets: " + socket.getSendBufferSize() + " Byte");
        } catch (BindException e) {
    		log.error("Port " + port +" auf dem Rechner schon in Benutzung, Bind Exception: "+ e);
    		System.out.println("Port " + port +" auf dem Rechner schon in Benutzung, Bind Exception: "+ e);
    		throw e;
    	} catch (SocketException e) {
            log.debug("Datagram-Socketfehler: " + e);
        }
    }

    /**
     * Empfangen einer Nachricht ueber UDP
     *
     * @param timeout Wartezeit in ms
     * @return Referenz auf Nachricht, die empfangen wurde
     * @throws IOException
     * @throws SocketTimeoutException
     * @throws StreamCorruptedException
     * @throws ClassNotFoundException
     * @throws Exception 
     */
    public Object receive(int timeout) throws IOException, SocketTimeoutException,
    		StreamCorruptedException, ClassNotFoundException, Exception {
        
    	// Maximale Wartezeit beim Empfang einstellen	
    	try {
    		socket.setSoTimeout(timeout);
        } catch (SocketException e) {
            log.error("RECEIVE: " + "Fehler beim Einstellen der maximalen Wartezeit");
            throw e;
        }
       
        byte[] bytes = new byte[65527];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);

         try {  	
             // Blockiert nur, bis Timeout abgelaufen ist
                
             //System.out.println("SoTimeout für Socket enabled/disabled: " + socket.getSoTimeout());
             socket.receive(packet);             
             log.debug("RECEIVE: Empfangene Datenlaenge:  " + packet.getLength());
         } catch (SocketTimeoutException e1) {
             log.debug("RECEIVE: " + "Socket Timeout, Anzahl Versuche: " );
         } catch (IOException e2) {
             log.error("RECEIVE: " + "Fehler beim Empfangen einer PDU ueber UDP");
             throw e2;
         }

        ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData());
        ObjectInputStream ois = new ObjectInputStream(bais);

        Object pdu;
        try {
            pdu = ois.readObject();

            remoteAddress = packet.getAddress();
            remotePort = packet.getPort();

            log.debug("RECEIVE: " + packet.getPort()
                    + "->"
                    + socket.getLocalPort());

        } catch (ClassNotFoundException e1) {
            log.error("RECEIVE: " + "ClassNotFoundException:", e1);
            //throw e1;
            return null;
        } catch (StreamCorruptedException e2) {
            log.error("RECEIVE: " + "Invalid Stream:", e2);
            //throw e2;
            return null;
        } catch (Exception e3) {
            log.error("RECEIVE: " + "Sonstiger schwerwiegender Fehler:", e3);
            //throw e3;
            return null;
        }
        return pdu;
    }

    /**
     * Senden einer Nachricht ueber UDP
     *
     * @param remoteAddress Adresse des Empfaengers
     * @param remotePort    Port des Empfaengers
     * @param pdu           Zu sendende PDU
     * @throws IOException
     */
    public void send(InetAddress remoteAddress, int remotePort, Object pdu) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ObjectOutputStream os = new ObjectOutputStream(out);
        os.writeObject(pdu);
        byte[] bytes = out.toByteArray();

        log.debug("SEND: zu sendende Bytes: " + bytes.length);

        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, remoteAddress, remotePort);

        log.debug("SEND: " + packet.getAddress() + ":" + packet.getPort());

        try {
            socket.send(packet);
        } catch (IOException e) {
            log.error("SEND: " + "Fehler beim Senden einer PDU");
            throw e;
        }
    }

    /**
     * Datagram-Socket schliessen
     */
    public void close() {
        log.debug("CLOSE: " + "Socket wird geschlossen");
        socket.close();
    }

    /**
     * @return Lokale Adresse
     */
    public String getLocalAddress() {
        return socket.getLocalAddress().getHostAddress();
    }

    /**
     * @return lokalen Port
     */
    public int getLocalPort() {
        return socket.getLocalPort();
    }

    /**
     * @return Remote Adresse
     */
    public InetAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * @return Remote Port
     */
    public int getRemotePort() {
        return remotePort;
    }

    public void setRemoteAddress(InetAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
    }

    public boolean isClosed() {
        return socket.isClosed();
    }
}