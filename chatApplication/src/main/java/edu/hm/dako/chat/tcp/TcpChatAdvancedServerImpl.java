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
 * <p>
 * Chat-Server-Implementierung
 *
 * @author Mandl
 */
public class TcpChatAdvancedServerImpl implements ChatServer {

    private static Log log = LogFactory.getLog(TcpChatAdvancedServerImpl.class);

    // Threadpool fuer Woekerthreads
    private final ExecutorService executorService;

    // Socket fuer den Listener, der alle Verbindungsaufbauwuensche der Clients entgegennimmt  
    private ServerSocket socket;

    // Gemeinsam fuer alle Workerthreads verwaltete Liste aller eingeloggten Clients
    private SharedChatClientList clients;

    // Startzeit fuer die RTT-Messung der Request-Bearbeitungsdauer eines Clients
    private long startTime;

    // Zaehler fuer Logouts und gesendete Events nur fuer Tests
    private static AtomicInteger logoutCounter = new AtomicInteger(0);
    private static AtomicInteger eventCounter = new AtomicInteger(0);

    /**
     * Konstruktor fuer den Server
     *
     * @param executorService Methoden-Provider
     * @param socket          Schnittstelle zwischen Anwender und Server
     */
    public TcpChatAdvancedServerImpl(ExecutorService executorService, ServerSocket socket) {
        log.debug("TcpChatAdvancedServerImpl konstruiert");
        this.executorService = executorService;
        this.socket = socket;
    }

    @Override
    public void start() {
        // Clientliste erzeugen
        clients = SharedChatClientList.getInstance();
        while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
            try {
                // Auf ankommende Verbindungsaufbauwuensche warten
                System.out.println("SimpleChatServer wartet auf Verbindungsanfragen von Clients...");
                Connection connection = socket.accept();
                log.debug("Neuer Verbindungsaufbauwunsch empfangen");

                // Neuen Workerthread starten
                executorService.submit(new ChatWorker(connection));
            } catch (Exception e) {
                log.error("Exception beim Entgegennehmen von Verbindungsaufbauwuenschen: " + e);
                ExceptionHandler.logException(e);
            }
        }
    }

    @Override
    public void stop() throws Exception {
        System.out.println("SimpleChatServer beendet sich");
        // Loeschen der Userliste
        clients.deleteAll();
        Thread.currentThread().interrupt();
        socket.close();
        log.debug("Listen-Socket geschlossen");
        executorService.shutdown();
        try {
            executorService.awaitTermination(10, TimeUnit.MINUTES);
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
        private ChatPDU copyOfConfirmPdu;

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
         * @param receivedPdu Empfangene PDU
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
         * @param receivedPdu Empfangene PDU
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
            log.debug("Aktuelle Clientliste: " + clientList);
            pdu.setClients(clientList);
            for (String s : new Vector<String>(clientList)) {
                log.debug("Fuer " + s + " wird Login- oder Logout-Event-PDU an alle aktiven Clients gesendet");

                ChatClientListEntry client = clients.getClient(s);
                try {
                    if (client != null) {
                        client.getConnection().send(pdu);
                        log.debug("Login- oder Logout-Event-PDU an " + client.getUserName() + " gesendet");
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

                //Erzeuge Warteliste fuer alle aktiven Clients
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

        /**
         * Logout-request bearbeiten: Waitlist fuer UserName erstellen, Liste der Clients updaten, Status ändern
         *
         * @param receivedPdu die erhaltene PDU
         * @throws Exception
         */
        private void handleLogoutRequest(ChatPDU receivedPdu) throws Exception {
            clients.createWaitList(receivedPdu.getUserName());
            clients.changeClientStatus(receivedPdu.getUserName(), ChatClientConversationStatus.UNREGISTERING);
            sendLoginListUpdateEvent(createLogoutEventPdu(receivedPdu));
        }

        /**
         * Sendet erhaltene PDU an alle aktiven Clients
         *
         * @param pdu Erhaltene PDU, die an alle Clients weitergeleitet werden muss
         */
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
                    }
                } catch (Exception e) {
                    log.debug("Senden einer Login- oder Logout-Event-PDU an " + s + " nicht moeglich");
                    ExceptionHandler.logException(e);
                }
            }
        }

        /**
         * Pruefen, ob sich der Client in den Wartelisten von anderen Clients befindet
         *
         * @return {@true} wenn sich der Client in fremden Wartelisten befindet
         * {@false} andernfalls
         */
        private boolean containedInAnothersWaitList() {
            Vector<String> clientNames = clients.getClientNameList();
            log.debug("Client names: " + clientNames);
            if (clientNames.size() > 0) {
                for (String s : clientNames) {
                    if (clients.existsClient(userName)) {
                        if (clients.getClient(s).getWaitList().contains(userName)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Verbindung zu einem Client ordentlich abbauen
         */
        private void closeConnection() {

            log.debug("Schliessen der Chat-Connection zum Client " + userName);
            clients.finish(userName);
            log.debug("Close Connection fuer " + userName + ", Laenge der Clientliste vor deleteClient: " + clients.size());
            // Bereinigen der Clientliste falls erforderlich
            while (clients.existsClient(userName)) {
                clients.deleteClient(userName);
                try {
                    Thread.sleep(10);
                } catch (Exception e) {

                }
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
            //Ueberpruefen, ob der Client für Ausloggen bereit ist
            //Ausloggen im Positivfall
            if (clients.existsClient(userName)
                    && clients.getClient(userName).getStatus() == ChatClientConversationStatus.UNREGISTERING
                    && !containedInAnothersWaitList()) {
                log.debug(userName + " ist in keiner Warteliste mehr enthalten, loggt sich aus");
                try {
                    copyOfConfirmPdu.setServerTime(System.nanoTime() - startTime);
                    connection.send(copyOfConfirmPdu);
                    log.debug("Logout-Response-PDU an " + userName + " gesendet");

                } catch (Exception e) {
                    log.error("Senden einer Logout-Response-PDU an " + userName + " nicht moeglich");
                    ExceptionHandler.logException(e);
                }
                finished = true;

            } else {

                // Warten auf naechste Nachricht
                ChatPDU receivedPdu;
                try {
                    receivedPdu = (ChatPDU) connection.receive();

                } catch (SocketException s) {
                    log.error("Socket-Error, Workerthread fuer User: " + userName + ". Client wird mit Gewalt gelöscht");
                    clients.deleteClientWithoutCondition(userName);
                    finished = true;
                    return;

                } catch (Exception e) {
                    log.error("Empfang einer Nachricht fehlgeschlagen, Workerthread fuer User: " + userName);
                    finished = true;
                    ExceptionHandler.logException(e);
                    return;
                }

                // Empfangene Nachricht bearbeiten
                try {
                    switch (receivedPdu.getPduType()) {

                        case ChatPDU.LOGIN_REQUEST:
                            // Neuer Client moechte sich einloggen, Client in Client-Liste eintragen
                            startTime = System.nanoTime();
                            log.debug("Login-Request-PDU fuer " + receivedPdu.getUserName() + " empfangen");
                            login(receivedPdu, connection);
                            break;

                        case ChatPDU.LOGOUT_REQUEST:
                            //Ein Client moechte sich ausloggen
                            startTime = System.nanoTime();
                            log.debug("Logout-Request-PDU fuer " + receivedPdu.getUserName() + " empfangen");
                            clients.changeClientStatus(receivedPdu.getUserName(), ChatClientConversationStatus.UNREGISTERING);
                            handleLogoutRequest(receivedPdu);
                            break;

                        case ChatPDU.CHAT_MESSAGE_REQUEST:
                            //Ein Client moechte eine Chat-Nachricht senden
                            startTime = System.nanoTime();
                            log.debug("Chat-Message-Request-PDU fuer " + receivedPdu.getUserName() + "empfangen");
                            clients.createWaitList(userName);
                            sendPduToAllActiveClients(createChatMessageEventPdu(receivedPdu));
                            break;

                        case ChatPDU.LOGIN_EVENT_CONFIRM:
                            //Ein Client sendet eine Login-Bestätigung fuer einen anderen Client
                            clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName);
                            if (clients.getClient(receivedPdu.getEventUserName()).getWaitList().isEmpty()) {
                                //Response-PDU aufbauen und senden
                                ChatPDU pdu = createLoginResponsePdu(receivedPdu);
                                try {
                                    if (clients.getClient(receivedPdu.getEventUserName()) != null) {
                                        pdu.setServerTime(System.nanoTime() - startTime);
                                        clients.getClient(receivedPdu.getEventUserName()).getConnection().send(pdu);
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
                            //Ein Client sendet eine Logout-Bestätigung fuer einen anderen Client
                            clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName);
                            //Response-PDU aufbauen und senden
                            if (userName.equals(receivedPdu.getEventUserName())) {
                                copyOfConfirmPdu = createLogoutResponsePdu(receivedPdu);
                            }
                            break;

                        case ChatPDU.CHAT_MESSAGE_EVENT_CONFIRM:
                            //EIn Cleint sendet eine Chat-Message-Bestätigung fuer einen anderen Client
                            clients.incrNumberOfReceivedChatEventConfirms(userName);
                            clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName);
                            if (clients.getWaitListSize(receivedPdu.getEventUserName()) == 0) {
                                //Response-PDU aufbauen und senden
                                ChatPDU pdu = createChatMessageResponsePdu(receivedPdu);
                                try {
                                    if (clients.getClient(receivedPdu.getEventUserName()) != null) {
                                        pdu.setServerTime(System.nanoTime() - startTime);
                                        clients.getClient(receivedPdu.getEventUserName()).getConnection().send(pdu);
                                        log.debug("Chat-Message-Response-PDU an " + receivedPdu.getEventUserName() + " gesendet");
                                    }
                                } catch (Exception e) {
                                    log.error("Senden einer Chat-Message-Response-PDU an " + receivedPdu.getEventUserName() + " nicht moeglich");
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
}





