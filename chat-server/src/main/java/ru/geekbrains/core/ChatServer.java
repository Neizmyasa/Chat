package ru.geekbrains.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.geekbrains.chat.common.MessageLibrary;
import ru.geekbrains.net.MessageSocketThread;
import ru.geekbrains.net.MessageSocketThreadListener;
import ru.geekbrains.net.ServerSocketThread;
import ru.geekbrains.net.ServerSocketThreadListener;

import java.net.Socket;
import java.util.ArrayList;
import java.util.Vector;

public class ChatServer implements ServerSocketThreadListener, MessageSocketThreadListener {

    private ServerSocketThread serverSocketThread;
    private ChatServerListener listener;
    private AuthController authController;
    private Vector<ClientSessionThread> clients = new Vector<>();
    private static final Logger LOGGER = LogManager.getLogger(ChatServer.class);

    public ChatServer(ChatServerListener listener) {
        this.listener = listener;
    }

    public void start(int port) {
        if (serverSocketThread != null && serverSocketThread.isAlive()) {
            return;
        }
        serverSocketThread = new ServerSocketThread(
                this, "Chat-Server-Socket-Thread", port, 2000);
        serverSocketThread.start();
        LOGGER.info("Server started");
        authController = new AuthController();
        authController.init();
        LOGGER.info("AuthController init Users");
    }

    public void stop() {
        if (serverSocketThread == null || !serverSocketThread.isAlive()) {
            return;
        }
        serverSocketThread.interrupt();
        disconnectAll();
        LOGGER.info("Server stopped");
    }

    /*
     * Server Socket Thread Listener Methods
     */

    @Override
    public void onClientConnected() {
        logMessage("Client connected");
        LOGGER.info("Client connected");
    }

    @Override
    public void onSocketAccepted(Socket socket) {
        clients.add(new ClientSessionThread(this, "ClientSessionThread", socket));
    }

    @Override
    public void onException(Throwable throwable) {
        throwable.printStackTrace();
        LOGGER.error(throwable.getMessage());
    }

    /*
     * Message Socket Thread Listener Methods
     */

    @Override
    public void onClientTimeout(Throwable throwable) {
    }

    @Override
    public void onSocketReady(MessageSocketThread thread) {
        logMessage("Socket ready");
        LOGGER.info("Socket ready");
    }

    @Override
    public void onSocketClosed(MessageSocketThread thread) {
        ClientSessionThread clientSession = (ClientSessionThread) thread;
        logMessage("Socket Closed");
        LOGGER.info("Socket Closed");
        clients.remove(thread);
        if (clientSession.isAuthorized() && !clientSession.isReconnected()) {
            sendToAllAuthorizedClients(MessageLibrary.getBroadcastMessage("server",
                    "User " + clientSession.getNickname() + " disconnected"));
        }
        sendToAllAuthorizedClients(MessageLibrary.getUserList(getUsersList()));
    }

    @Override
    public void onMessageReceived(MessageSocketThread thread, String msg) {
        ClientSessionThread clientSession = (ClientSessionThread) thread;
        if (clientSession.isAuthorized()) {
            processAuthorizedUserMessage(msg);
        } else {
            processUnauthorizedUserMessage(clientSession, msg);
        }
    }

    @Override
    public void onException(MessageSocketThread thread, Throwable throwable) {
        throwable.printStackTrace();
    }

    private void processAuthorizedUserMessage(String msg) {
        logMessage(msg);
        for (ClientSessionThread client : clients) {
            if (!client.isAuthorized()) {
                continue;
            }
            client.sendMessage(msg);
            LOGGER.info(client.getNickname() + " send message - " + msg);
        }
    }

    private void sendToAllAuthorizedClients(String msg) {
        for (ClientSessionThread client : clients) {
            if (!client.isAuthorized()) {
                continue;
            }
            client.sendMessage(msg);
        }
    }

    private void processUnauthorizedUserMessage(ClientSessionThread clientSession, String msg) {
        String[] arr = msg.split(MessageLibrary.DELIMITER);
        if (arr.length < 4 ||
                !arr[0].equals(MessageLibrary.AUTH_METHOD) ||
                !arr[1].equals(MessageLibrary.AUTH_REQUEST)) {
            clientSession.authError("Incorrect request: " + msg);
            return;
        }
        String login = arr[2];
        String password = arr[3];
        String nickname = authController.getNickname(login, password);
        if (nickname == null) {
            clientSession.authDeny();
            return;
        } else {
            ClientSessionThread oldClientSession = findClientSessionByNickname(nickname);
            clientSession.authAccept(nickname);
            if (oldClientSession == null) {
                sendToAllAuthorizedClients(MessageLibrary.getBroadcastMessage("Server", nickname + " connected"));
            } else {
                oldClientSession.setReconnected(true);
                clients.remove(oldClientSession);
            }
        }
        sendToAllAuthorizedClients(MessageLibrary.getUserList(getUsersList()));
    }

    public void disconnectAll() {
        ArrayList<ClientSessionThread> currentClients = new ArrayList<>(clients);
        for (ClientSessionThread client : currentClients) {
            client.close();
            clients.remove(client);
        }
        LOGGER.info("disconnect all");
    }

    private void logMessage(String msg) {
        listener.onChatServerMessage(msg);
    }

    public String getUsersList() {
        StringBuilder sb = new StringBuilder();
        for (ClientSessionThread client : clients) {
            if (!client.isAuthorized()) {
                continue;
            }
            sb.append(client.getNickname()).append(MessageLibrary.DELIMITER);
        }
        return sb.toString();
    }

    private ClientSessionThread findClientSessionByNickname(String nickname) {
        for (ClientSessionThread client : clients) {
            if (!client.isAuthorized()) {
                continue;
            }
            if (client.getNickname().equals(nickname)) {
                return client;
            }
        }
        return null;
    }
}
