import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

public class Server {
    private final HashMap<String, Integer>             clients           = new HashMap<>();
    private final HashMap<String, List<ClientThread>>  servers           = new HashMap<>();
    private final HashMap<String, Integer>             serverNameAndPort = new HashMap<>();
    private final HashMap<String, PendingTransfer>     pendingTransfers  = new HashMap<>();
    private final HashMap<String, String>              fileReceiverMap   = new HashMap<>();

    // stores the UDP port a caller is listening on while we wait for the callee to accept.
    // key = caller username, value = caller's UDP port as a string
    private final HashMap<String, String>              callInfoMap       = new HashMap<>();

    private int port = 5001;
    private ServerSocket serverSocket;

    public static class PendingTransfer {
        public ClientThread sender;
        public String filename;
        public long fileSize;

        public PendingTransfer(ClientThread sender, String filename, long fileSize) {
            this.sender   = sender;
            this.filename = filename;
            this.fileSize = fileSize;
        }
    }

    public Server(String name, int port) {
        OnlineCounterSafe onlineCounter = new OnlineCounterSafe();
        try {
            FileOutputStream file = new FileOutputStream("discord-system-313/src/namesInUse.txt");
            file.write("\n".getBytes());
            file.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            ServerSocket ss = new ServerSocket(port);
            this.serverSocket = ss;
            serverNameAndPort.put(name, ss.getLocalPort());

            // file relay on port 5002 — handles raw binary file transfers
            FileRelayServer fileRelay = new FileRelayServer(5002, this);
            new Thread(fileRelay).start();

            // video streaming and video calls go directly P2P over UDP —
            // the main server on 5001 only brokers the IP/port exchange


            // automatically create chatrooms based on server logs that exist (so dont have to keep using /create)
            File dir = new File("discord-system-313/src/ServerLogs");
            File [] logsDir = dir.listFiles();
            if (logsDir != null) {      // Check the ServerLogs directory for server names
                for (File log : logsDir) {
                    if (log.getName().equals("main.txt")) {     // main is created already automatically
                        continue;
                    } else {
                        String chatName = log.getName().replaceFirst("[.][^.]+$", "");  // use regex to get server name
                        servers.put(chatName, new ArrayList<>());
                    }
                }
            }


            System.out.println("Server is running; waiting for a client...");

            while (!ss.isClosed()) {
                Socket socket = ss.accept();
                ClientThread serverThread = new ClientThread(socket, this, onlineCounter, serverNameAndPort.get(name));
                new Thread(serverThread).start();
                System.out.println(clients.size() + " clients connected.");
            }

        } catch (IOException e) {
            this.closeServerSocket();
            throw new RuntimeException(e);
        }
    }

    // ---- File transfer methods ----

    public synchronized void addPendingTransfer(String receiverUsername, ClientThread sender, String filename, long fileSize) {
        pendingTransfers.put(receiverUsername, new PendingTransfer(sender, filename, fileSize));
    }

    public synchronized void removePendingTransfer(String receiverUsername) {
        pendingTransfers.remove(receiverUsername);
    }

    public synchronized void setFileReceiver(String senderUsername, String receiverUsername) {
        fileReceiverMap.put(senderUsername, receiverUsername);
    }

    public synchronized String getFileReceiver(String senderUsername) {
        return fileReceiverMap.get(senderUsername);
    }

    public synchronized void removeFileReceiver(String senderUsername) {
        fileReceiverMap.remove(senderUsername);
    }

    // ---- Video call brokering methods ----
    // These only hold data temporarily while the callee decides whether to accept.
    // Once both sides have each other's IP and UDP port, the call is fully P2P
    // and the server is no longer involved.

    /**
     * Called when a /videocall command arrives. Stores the caller's UDP port
     * so we can pass it to the callee once they accept.
     */
    public synchronized void setCallInfo(String callerUsername, String callerUdpPort) {
        callInfoMap.put(callerUsername, callerUdpPort);
    }

    /**
     * Retrieve the caller's UDP port when the callee accepts.
     */
    public synchronized String getCallInfo(String callerUsername) {
        return callInfoMap.get(callerUsername);
    }

    /**
     * Clean up after the call has been set up or rejected.
     */
    public synchronized void removeCallInfo(String callerUsername) {
        callInfoMap.remove(callerUsername);
    }

    // ---- General server helpers ----

    public synchronized ClientThread getClientThread(String username) {
        for (ClientThread ct : ClientThread.clientThread) {
            if (ct.clientUsername.equals(username)) return ct;
        }
        return null;
    }

    public synchronized List<ClientThread> getServerClientList(String server) {
        return servers.get(server);
    }

    public synchronized int getPort() {
        this.port++;
        System.out.println(this.port);
        return this.port;
    }

    public synchronized void addToChat(String server, ClientThread client) {
        if (!servers.containsKey(server)) {
            servers.put(server, new ArrayList<>());
        }
        servers.get(server).add(client);
    }

    public synchronized HashMap<String, Integer> getClients() {
        return clients;
    }

    public synchronized void addClientToServer(String userName, Integer serverNumber) {
        clients.put(userName, serverNumber);
    }

    public synchronized HashMap<String, List<ClientThread>> getServers() {
        return servers;
    }

    public synchronized void removeFromChat(String server, ClientThread client) {
        if (servers.containsKey(server)) {
            servers.get(server).remove(client);
        }
    }

    public synchronized void removeClient(String out) {
        clients.remove(out);
    }

    public void closeServerSocket() {
        ServerSocket ss = this.serverSocket;
        try {
            if (ss != null) {
                this.serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        try {
            Server server = new Server("main", 5001);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}