package edu.hm.dako.chat.tcp;

import edu.hm.dako.chat.common.*;
import edu.hm.dako.chat.connection.Connection;
import edu.hm.dako.chat.connection.ServerSocket;
import edu.hm.dako.chat.server.ChatServer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.net.SocketException;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p/>
 * Chat-Server-Implementierung
 *
 * @author Mandl
 */
public class TcpChatSimpleServerImpl implements ChatServer {

    private static Log log = LogFactory.getLog(TcpChatSimpleServerImpl.class);

    // Threadpool fuer Woekerthreads
    private final ExecutorService executorService;

    // Socket fuer den Listener, der alle Verbindungsaufbauwuensche der Clients entgegennimmt  
    private ServerSocket socket;

    // Gemeinsam fuer alle Workerthreads verwaltete Liste aller eingeloggten Clients
    private SharedChatClientList clients;

    // Startzeit fuer die RTT-Messung der Request-Bearbeitungsdauer eines Clients
    private long startTime;

    // Zaehler fuer Logouts und gesendete Events nur zum Tests
    private static AtomicInteger logoutCounter = new AtomicInteger(0);
    private static AtomicInteger eventCounter = new AtomicInteger(0);

    public TcpChatSimpleServerImpl(ExecutorService executorService, ServerSocket socket) {
        log.debug("TcpChatSimpleServerImpl konstruiert");
        this.executorService = executorService;
        this.socket = socket;
    }

    @Override
    public void start() {

        clients = SharedChatClientList.getInstance();  // Clientliste erzeugen    
        while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) { ///while Thread ist aktiv und Socket ist ge�ffnet
            try {
                // Auf ankommende Verbindungsaufbauwuensche warten
                System.out.println("SimpleChatServer wartet auf Verbindungsanfragen von Clients...");
                Connection connection = socket.accept(); ///WIE FUNKTIONIERT DAS?
                log.debug("Neuer Verbindungsaufbauwunsch empfangen");

                // Neuen Workerthread starten
                executorService.submit(new ChatWorker(connection)); ///muss so viele Chatworkers erzeugen wie viele Clients vorghanden. Das macht Class Chatworker
            } catch (Exception e) {
                log.error("Exception beim Entgegennehmen von Verbindungsaufbauwuenschen: " + e);
                ExceptionHandler.logException(e);
            }
        }
    }

    @Override
    public void stop() throws Exception {
        System.out.println("SimpleChatServer beendet sich");
        clients.deleteAll(); // Loeschen der Userliste
        Thread.currentThread().interrupt();
        socket.close();
        log.debug("Listen-Socket geschlossen");
        executorService.shutdown(); /// nichts neues kann initiiert werden, aber die laufenden Threads werden weier abgearbeitet
        try {
            executorService.awaitTermination(10, TimeUnit.MINUTES); ///nachdem alle laufenden Threads abgearbeitet oder sp�testens nach 10 min schlie�en
        } catch (InterruptedException e) {
            log.error("Das Beenden des ExecutorService wurde unterbrochen");
            ExceptionHandler.logExceptionAndTerminate(e);
        }
        log.debug("Threadpool freigegeben");
    }

    /**
     * Worker-Thread zur serverseitigen Bedienung einer Session mit einem Client.
     * Jedem Client wird serverseitig ein Worker-Thread zugeordnet.
     *
     * @author Mandl
     */
    private class ChatWorker implements Runnable {

        private Connection connection; // Verbindungs-Handle
        private boolean finished = false;
        private String userName; // Username des durch den Worker-Thread bedienten Clients

        private ChatWorker(Connection con) {
            this.connection = con;
        }

        @Override
        public void run() {

            log.debug("ChatWorker-Thread erzeugt, Threadname: " + Thread.currentThread().getName());
            while (!finished && !Thread.currentThread().isInterrupted()) {
                try {
                    // Warte auf naechste Nachricht des Clients und fuehre entsprechende Aktion aus
                    handleIncomingMessage();
                } catch (Exception e) {
                    log.error("Exception waehrend der Nachrichtenverarbeitung");
                    ExceptionHandler.logException(e);
                }
            }
            log.debug(Thread.currentThread().getName() + " beendet sich");
            closeConnection();
        }

        /**
         * Erzeugen einer Logout-Event-PDU
         *
         * @param receivedPdu Empfangene PDU (Logout-Request-PDU)
         * @return Erzeugte PDU
         */
        private ChatPDU createLogoutEventPdu(ChatPDU receivedPdu) {

            ChatPDU pdu = new ChatPDU();
            pdu.setPduType(ChatPDU.LOGOUT_EVENT);
            pdu.setUserName(userName);
            pdu.setEventUserName(userName);
            pdu.setServerThreadName(Thread.currentThread().getName());
            pdu.setClientThreadName(receivedPdu.getClientThreadName());
            pdu.setClientStatus(ChatClientConversationStatus.UNREGISTERING);
            return pdu;
        }

        /**
         * Erzeugen einer Login-Event-PDU
         *
         * @param receivedPdu Empfangene PDU (Login-Request-PDU)
         * @return Erzeugte PDU
         */
        private ChatPDU createLoginEventPdu(ChatPDU receivedPdu) {

            ChatPDU pdu = new ChatPDU();
            pdu.setPduType(ChatPDU.LOGIN_EVENT);
            pdu.setServerThreadName(Thread.currentThread().getName());
            pdu.setClientThreadName(receivedPdu.getClientThreadName());
            pdu.setUserName(userName);
            pdu.setEventUserName(receivedPdu.getUserName());
            pdu.setUserName(receivedPdu.getUserName());
            pdu.setClientStatus(ChatClientConversationStatus.REGISTERING);
            return pdu;
        }

        /**
         * Erzeugen einer Login-Response-PDU
         *
         * @param receivedPdu Empfangene PDU (Login-Request-PDU)
         * @return Erzeugte PDU
         */
        private ChatPDU createLoginResponsePdu(ChatPDU receivedPdu) {

            ChatPDU pdu = new ChatPDU();

            pdu.setPduType(ChatPDU.LOGIN_RESPONSE);
            pdu.setServerThreadName(Thread.currentThread().getName());
            pdu.setClientThreadName(receivedPdu.getClientThreadName());
            pdu.setUserName(receivedPdu.getUserName());

            ChatClientListEntry client = clients.getClient(receivedPdu.getUserName());

            if (client != null) {
                //pdu.setServerTime(System.nanoTime() - client.getStartTime());
                pdu.setClientStatus(client.getStatus());
            } else {
                pdu.setClientStatus(ChatClientConversationStatus.REGISTERED);
            }
            return pdu;
        }

        /**
         * Erzeugen einer Chat-Message-Event-PDU
         *
         * @param receivedPdu (Chat-Message-Request-PDU)
         * @return Erzeugte PDU
         */
        private ChatPDU createChatMessageEventPdu(ChatPDU receivedPdu) {

            ChatPDU pdu = new ChatPDU();
            pdu.setPduType(ChatPDU.CHAT_MESSAGE_EVENT);
            pdu.setServerThreadName(Thread.currentThread().getName());
            pdu.setClientThreadName(receivedPdu.getClientThreadName());
            pdu.setUserName(userName);
            pdu.setEventUserName(receivedPdu.getUserName());
            pdu.setSequenceNumber(receivedPdu.getSequenceNumber());
            pdu.setClientStatus(ChatClientConversationStatus.REGISTERED);
            pdu.setMessage(receivedPdu.getMessage());
            return pdu;
        }

        /**
         * Erzeugen einer Chat-Message-Response-PDU
         *
         * @param receivedPdu (Chat-Message-Request-PDU)
         * @return Erzeugte PDU
         */
        private ChatPDU createChatMessageResponsePdu(ChatPDU receivedPdu) {

            ChatPDU pdu = new ChatPDU();
            pdu.setPduType(ChatPDU.CHAT_MESSAGE_RESPONSE);
            pdu.setServerThreadName(Thread.currentThread().getName());
            pdu.setClientThreadName(receivedPdu.getClientThreadName());
            pdu.setEventUserName(receivedPdu.getEventUserName());
            pdu.setUserName(receivedPdu.getUserName());
            pdu.setClientStatus(ChatClientConversationStatus.REGISTERED);
            ChatClientListEntry client = clients.getClient(receivedPdu.getUserName());

            if (client != null) {
                pdu.setClientStatus(client.getStatus());
                //pdu.setServerTime(System.nanoTime() - client.getStartTime()); alt
                pdu.setServerTime(System.nanoTime() - startTime); //neu
                pdu.setSequenceNumber(client.getNumberOfReceivedChatMessages());
                pdu.setNumberOfSentEvents(client.getNumberOfSentEvents());
                pdu.setNumberOfLostEventConfirms(client.getNumberOfLostEventConfirms());
                pdu.setNumberOfEventReceivedConfirms(client.getNumberOfReceivedEventConfirms());
                pdu.setNumberOfRetries(client.getNumberOfRetries());
                pdu.setNumberOfReceivedChatMessages(client.getNumberOfReceivedChatMessages());
            }
            return pdu;
        }

        /**
         * Erzeugen einer Logout-Response-PDU
         *
         * @param //pdu Empfangene PDU
         * @return Erzeugte PDU
         */
        private ChatPDU createLogoutResponsePdu(ChatPDU receivedPdu) {

            ChatPDU pdu = new ChatPDU();
            pdu.setPduType(ChatPDU.LOGOUT_RESPONSE);
            pdu.setServerThreadName(Thread.currentThread().getName());
            pdu.setClientThreadName(receivedPdu.getClientThreadName());
            pdu.setUserName(receivedPdu.getUserName());
            pdu.setClientStatus(ChatClientConversationStatus.UNREGISTERED);

            ChatClientListEntry client = clients.getClient(receivedPdu.getUserName());
            if (client != null) {
                pdu.setClientStatus(client.getStatus());
                //pdu.setServerTime(System.nanoTime() - client.getStartTime());
                pdu.setNumberOfSentEvents(client.getNumberOfSentEvents());
                pdu.setNumberOfLostEventConfirms(client.getNumberOfLostEventConfirms());
                pdu.setNumberOfEventReceivedConfirms(client.getNumberOfReceivedEventConfirms());
                pdu.setNumberOfRetries(client.getNumberOfRetries());
                pdu.setNumberOfReceivedChatMessages(client.getNumberOfReceivedChatMessages());
            }
            return pdu;
        }

        /**
         * Erzeugen einer Login-Response-PDU mit Fehlermeldung
         *
         * @param //pdu Empfangene PDU
         * @return Erzeugte PDU
         */
        private ChatPDU createLoginErrorResponsePdu(ChatPDU receivedPdu, int errorCode) {

            ChatPDU pdu = new ChatPDU();
            pdu.setPduType(ChatPDU.LOGIN_RESPONSE);
            pdu.setServerThreadName(Thread.currentThread().getName());
            pdu.setClientThreadName(receivedPdu.getClientThreadName());
            pdu.setUserName(receivedPdu.getUserName());
            pdu.setClientStatus(ChatClientConversationStatus.UNREGISTERED);
            pdu.setErrorCode(errorCode);
            return pdu;
        }

        /**
         * Senden eines Login-List-Update-Event an alle angemeldeten Clients
         *
         * @param pdu Zu sendende PDU
         */
        private void sendLoginListUpdateEvent(ChatPDU pdu) {
            // Liste der eingeloggten User ermitteln
            Vector<String> clientList = clients.getClientNameList();

            // Beim Logout-Event den Client, der sich abmeldet, ausschlie�en
            if (pdu.getPduType() == ChatPDU.LOGOUT_EVENT) {
                clientList.remove(pdu.getEventUserName());
            }

            log.debug("Aktuelle Clientliste: " + clientList);

            pdu.setClients(clientList);

            for (String s : new Vector<String>(clientList)) {
                log.debug("Fuer " + s + " wird Login- oder Logout-Event-PDU an alle aktiven Clients gesendet");

                ChatClientListEntry client = clients.getClient(s);
                try {
                    if (client != null) {

                        client.getConnection().send(pdu);
                        log.debug("Login- oder Logout-Event-PDU an " + client.getUserName() + " gesendet");
                        //clients.incrNumberOfSentChatEvents(client.getUserName());
                        //eventCounter.getAndIncrement(); /// Anzahl der vom Server gesendeten EVENT-PDUs
                    }
                } catch (Exception e) {
                    log.debug("Senden einer Login- oder Logout-Event-PDU an " + s + " nicht moeglich");
                    ExceptionHandler.logException(e);
                }
            }
        }

        /**
         * Login-Request bearbeiten: Neuen Client anlegen, alle Clients informieren, Response senden
         *
         * @param receivedPdu Empfangene PDU
         * @param con         Verbindung zum neuen Client
         */
        private void login(ChatPDU receivedPdu, Connection con) {

            ChatPDU pdu;

            if (!clients.existsClient(receivedPdu.getUserName())) {
                log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
                ChatClientListEntry client = new ChatClientListEntry(receivedPdu.getUserName(), con);
                client.setLoginTime(System.nanoTime());
                clients.createClient(receivedPdu.getUserName(), client);
                clients.changeClientStatus(receivedPdu.getUserName(), ChatClientConversationStatus.REGISTERING);
                log.debug("User " + receivedPdu.getUserName() + " nun in Clientliste");
                userName = receivedPdu.getUserName();
                Thread.currentThread().setName(receivedPdu.getUserName());
                log.debug("Laenge der Clientliste: " + clients.size());

                /// Create WaitList f�r alle aktiven Clients
                clients.createWaitList(receivedPdu.getUserName());

                // Login-Event an alle Clients (auch an den gerade aktuell anfragenden) senden
                pdu = createLoginEventPdu(receivedPdu);
                sendLoginListUpdateEvent(pdu);


            } else {
                // User bereits angemeldet, Fehlermeldung an Client senden, Fehlercode an Client senden
                pdu = createLoginErrorResponsePdu(receivedPdu, ChatPDU.LOGIN_ERROR);

                try {
                    con.send(pdu);
                    log.debug("Login-Response-PDU an " + receivedPdu.getUserName() + " mit Fehlercode " + ChatPDU.LOGIN_ERROR + " gesendet");
                } catch (Exception e) {
                    log.debug("Senden einer Login-Response-PDU an " + receivedPdu.getUserName() + " nicth moeglich");
                    ExceptionHandler.logExceptionAndTerminate(e);
                }
            }
        }

        private void handleLogoutRequest(ChatPDU receivedPdu, Connection con) throws Exception {
            // Create WaitList f�r alle aktiven Clients
            /*Vector<String> clientList = clients.getClientNameList();
            for(String s : new Vector<String>(clientList)) {
                clients.createWaitList(s);
            }*/

            clients.createWaitList(receivedPdu.getUserName());
            // Logout-Event an alle Clients (auch an den gerade aktuell anfragenden) senden
            /*ChatPDU pdu = createLogoutEventPdu(receivedPdu);
            sendLoginListUpdateEvent(pdu);
*/
            sendLoginListUpdateEvent(createLogoutEventPdu(receivedPdu));
            //new State = UNREGISTERING
            clients.changeClientStatus(receivedPdu.getUserName(), ChatClientConversationStatus.UNREGISTERING);


            /*pdu = createLogoutResponsePdu(receivedPdu); ------> zum LOGOUT_EVENT_EVENT_CONFIRM
            try {
                if (clients.getClient(userName) != null) {
                    con.send(pdu);
                    log.debug("Logout-Response-PDU an " + receivedPdu.getUserName() + " gesendet");
                }
            } catch (Exception e) {
                log.error("Senden einer Logout-Response-PDU an " + receivedPdu.getUserName() + " nicht moeglich");
                ExceptionHandler.logException(e);
            }
            //closeConnection();
            //finished = true;*/
        }


        private void logout(ChatPDU receivedPdu, Connection con) {
            ///closeConnection();
            finished = true;
        }

        private void sendPduToAllActiveClients(ChatPDU pdu) {
            Vector<String> clientList = clients.getClientNameList();
            for (String s : new Vector<String>(clientList)) {
                log.debug("Fuer " + s + " wird Login- oder Logout-Event-PDU an alle aktiven Clients gesendet");

                ChatClientListEntry client = clients.getClient(s);
                try {
                    if (client != null) {

                        client.getConnection().send(pdu);
                        log.debug("Login- oder Logout-Event-PDU an " + client.getUserName() + " gesendet");
                        clients.incrNumberOfSentChatEvents(client.getUserName());
                        //eventCounter.getAndIncrement();
                    }
                } catch (Exception e) {
                    log.debug("Senden einer Login- oder Logout-Event-PDU an " + s + " nicht moeglich");
                    ExceptionHandler.logException(e);
                }
            }
        }

        /**
         * Verbindung zu einem Client ordentlich abbauen
         */
        private void closeConnection() {

            log.debug("Schliessen der Chat-Connection zum Client " + userName);

            // Bereinigen der Clientliste falls erforderlich

            if (clients.existsClient(userName)) {
                clients.finish(userName);
                log.debug("Close Connection fuer " + userName + ", Laenge der Clientliste vor deleteClient: " + clients.size());
                clients.deleteClient(userName);
                log.debug("Laenge der Clientliste nach deleteClient fuer: " + userName + ": " + clients.size());
            }

            try {
                connection.close();
            } catch (Exception e) {
                ExceptionHandler.logException(e);
            }
        }

        /**
         * Verarbeitung einer ankommenden Nachricht eines Clients (Zustandsautomat des Servers)
         *
         * @throws Exception
         */
        private void handleIncomingMessage() throws Exception {

            // prüfen auf clients.isFinish
            // prüfen ob noch in anderer waitlist (prüft nicht, sondern löscht die Wartelisten-Einträge falls der Client bereits ausgeloggt)
            // falls nein -> logoutResponse senden, aus clients löschen und finished=true setzen

            // Warten auf naechste Nachricht

            //gc

            ChatPDU receivedPdu;
            try {
                receivedPdu = (ChatPDU) connection.receive();
                startTime = System.nanoTime(); // Zeitmessung fuer RTT starten

            } catch (SocketException s) {
                log.error("Socket-Error, Workerthread fuer User: " + userName + ". Client wird mit Gewalt gelöscht");
                clients.forceDeleteClient(userName);
                finished = true;
                return;

            } catch (Exception e) {
                log.error("Empfang einer Nachricht fehlgeschlagen, Workerthread fuer User: " + userName);
                ///clients.deleteClient(userName); ///den plötzlich ausgeloggten Client aus allen Wartelisten löschen
                finished = true;
                ExceptionHandler.logException(e);
                return;
            }

            // Empfangene Nachricht bearbeiten
            try {
                switch (receivedPdu.getPduType()) {

                    case ChatPDU.LOGIN_REQUEST:
                        // Neuer Client moechte sich einloggen
                        // Client in Client-Liste eintragen

                        log.debug("Login-Request-PDU fuer " + receivedPdu.getUserName() + " empfangen");
                        login(receivedPdu, connection);
                        //clients.changeClientStatus(receivedPdu.getUserName(), ChatClientConversationStatus.REGISTERED);
                        break;

                    case ChatPDU.LOGOUT_REQUEST:
                        log.debug("Logout-Request-PDU fuer " + receivedPdu.getUserName() + " empfangen");
                        if (clients.isClientFinished(receivedPdu.getUserName())) {

                            ChatPDU pdu = createLogoutResponsePdu(receivedPdu);
                            try {
                                if (clients.getClient(receivedPdu.getUserName()) != null) { /// war:(userName)
                                    clients.getClient(receivedPdu.getUserName()).getConnection().send(pdu);
                                    log.debug("Logout-Response-PDU an " + receivedPdu.getUserName() + " gesendet");
                                } else {
                                    log.debug("Logout für" + receivedPdu.getEventUserName() + " ist nicht moeglich");
                                }
                            } catch (Exception e) {
                                log.error("Senden einer Logout-Response-PDU an " + receivedPdu.getEventUserName() + " nicht moeglich");
                                ExceptionHandler.logException(e);
                            }
                            clients.changeClientStatus(receivedPdu.getUserName(), ChatClientConversationStatus.UNREGISTERED);
                            if (clients.deleteClient(userName)) {
                                ///closeConnection();
                                finished = true;
                            }
                        } else {
                            //Logout-Event an alle User schicken
                            handleLogoutRequest(receivedPdu, connection);
                        }
                        break;

                    case ChatPDU.CHAT_MESSAGE_REQUEST:
                        //long startTime = System.nanoTime() - client.getStartTime;
                        startTime = System.nanoTime();
                        log.debug("Chat-Message-Request-PDU fuer " + receivedPdu.getUserName() + "empfangen");
                        sendPduToAllActiveClients(createChatMessageEventPdu(receivedPdu));
                        //long ServerTime = System.nanoTime() - startTime;
                        connection.send(createChatMessageResponsePdu(receivedPdu));
                        break;

                    case ChatPDU.LOGIN_EVENT_CONFIRM:

                        clients.deleteWaitListEntry(userName, receivedPdu.getUserName()); /// ?? uaserName, entryName
                        if (clients.getWaitListSize(userName) == 0) {
                            //Response-PDU senden
                            ChatPDU pdu = createLoginResponsePdu(receivedPdu);
                            try {
                                if (clients.getClient(userName) != null) { /// receivedPdu.getUserName
                                    clients.getClient(receivedPdu.getEventUserName()).getConnection().send(pdu);
                                    //connection.send(pdu);
                                    log.debug("Login-Response-PDU an " + receivedPdu.getUserName() + " gesendet");
                                }
                            } catch (Exception e) {
                                log.error("Senden einer Login-Response-PDU an " + receivedPdu.getUserName() + " nicht moeglich");
                                ExceptionHandler.logException(e);
                            }
                            clients.changeClientStatus(receivedPdu.getUserName(), ChatClientConversationStatus.REGISTERED);
                        }

                        break;

                    case ChatPDU.LOGOUT_EVENT_CONFIRM:

                        clients.deleteWaitListEntry(userName, receivedPdu.getUserName()); /// löschen des Clients, der die Confrm zugeschickt hat, aus der Warteliste
                        if (clients.isClientFinished(receivedPdu.getEventUserName())) {
                            //if (clients.getWaitListSize(userName) == 0) { ///oder receivedPdu.getEventUserName()?
                            //Response-PDU senden
                            ChatPDU pdu = createLogoutResponsePdu(receivedPdu);
                            try {
                                if (clients.getClient(receivedPdu.getEventUserName()) != null) {
                                    clients.getClient(receivedPdu.getEventUserName()).getConnection().send(pdu);
                                    //connection.send(pdu);
                                    log.debug("Logout-Response-PDU an " + receivedPdu.getEventUserName() + " gesendet");
                                } else {
                                    log.debug("Client " + receivedPdu.getEventUserName() + " existiert nicht");
                                }
                            } catch (Exception e) {
                                log.error("Senden einer Logout-Response-PDU an " + receivedPdu.getEventUserName() + " nicht moeglich");
                                ExceptionHandler.logException(e);
                            }
                            if (clients.deleteClient(receivedPdu.getEventUserName())) {
                                clients.changeClientStatus(receivedPdu.getEventUserName(), ChatClientConversationStatus.UNREGISTERED);
                                logout(receivedPdu, connection);
                            }
                        } else {
                            log.debug("Client " + receivedPdu.getEventUserName() + " ist noch nicht fertig zum Ausloggen");
                        }
                        break;

                    case ChatPDU.CHAT_MESSAGE_EVENT_CONFIRM:

                        clients.incrNumberOfReceivedChatEventConfirms(userName); ///F�r Statistik


                        clients.deleteWaitListEntry(userName, receivedPdu.getUserName()); /// ?? uaserName, entryName
                        if (clients.getWaitListSize(userName) == 0) {
                            //Response-PDU senden
                            ChatPDU pdu = createChatMessageResponsePdu(receivedPdu);
                            try {
                                if (clients.getClient(userName) != null) {
                                    connection.send(pdu);
                                    log.debug("Chat-Message-Response-PDU an " + receivedPdu.getUserName() + " gesendet");
                                }
                            } catch (Exception e) {
                                log.error("Senden einer Chat-Message-Response-PDU an " + receivedPdu.getUserName() + " nicht moeglich");
                                ExceptionHandler.logException(e);
                            }
                        }
                        break;

                    default:
                        log.debug("Falsche PDU empfangen von Client: " + receivedPdu.getUserName() + ", PduType: " + receivedPdu.getPduType());
                        break;
                }
            } catch (Exception e) {
                log.error("Exception bei der Nachrichtenverarbeitung");
                ExceptionHandler.logExceptionAndTerminate(e);
            }
        }
    }
}


