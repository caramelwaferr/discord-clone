import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class ClientThread implements Runnable {
    public static final List<ClientThread> clientThread = Collections.synchronizedList(new ArrayList<>());
    private static final ReentrantLock clientsLock = new ReentrantLock();
    private Socket socket;
    private Server serverMain;
    private final List<String> clients = new ArrayList<>();
    private BufferedReader in;
    public BufferedWriter out;
    private OnlineCounterSafe onlineCounter;
    public String clientUsername;
    private boolean closed = false;
    private static boolean ServerLog = false;
    private String serverIn;
    private int serverNumber;

    public ClientThread(Socket socket, Server serverMain, OnlineCounterSafe onlineCounter, Integer serverNumber) {
        try {
            this.socket       = socket;
            this.serverMain   = serverMain;
            this.serverNumber = serverNumber;
            this.onlineCounter = onlineCounter;
            this.out           = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            this.in            = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.clientUsername = in.readLine();
            this.serverIn       = "main";
            synchronized (clientThread) {
                clientThread.add(this);
            }
        } catch (IOException e) {
            this.closeClientThread(socket, in, out);
        }
    }

    public void broadCastMessage(String messageToSend) {
        List<ClientThread> clientThreads = serverMain.getServerClientList(serverIn);
        synchronized (clientThreads) {
            for (ClientThread c : clientThreads) {
                try {
                    if (messageToSend.equals("q")) {
                        closeClientThread(socket, in, out);
                        return;
                    }
                    if (!(c.clientUsername.equals(this.clientUsername)) && !(messageToSend.isBlank())) {
                        c.out.write(clientUsername + ": " + messageToSend);
                        c.out.newLine();
                        c.out.flush();
                    }
                } catch (IOException e) {
                    c.closeClientThread(socket, in, out);
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            System.out.println(clientUsername + " connected at " + socket.getInetAddress());

            serverMain.addClientToServer(clientUsername, serverNumber);
            serverMain.addToChat("main", this);
            onlineCounter.incrementOnline();

            File test = new File("discord-system-313/src/ServerLogs/" + serverIn + ".txt");
            try {
                if (test.createNewFile()) {
                    System.out.println("File created: " + test.getName());
                }
            } catch (IOException e) {
                System.out.println("Error creating file.");
                e.printStackTrace();
            }

            try (Scanner myReader = new Scanner(test)) {
                synchronized (clientThread) {
//                    if (!ServerLog) {
//                        ServerLog = true;

                        // Read all texts
                        try (Scanner myServerReader = new Scanner(test)) {
                            while (myServerReader.hasNextLine()) {
                                System.out.println(myServerReader.nextLine());
                            }
                        } catch (IOException e) {
                            System.out.println("Error reading messages.");
                            e.printStackTrace();
                        }
//                    }
                }
                while (myReader.hasNextLine()) {
                    out.write(myReader.nextLine());
                    out.newLine();
                }
                out.flush();
            } catch (IOException e) {
                System.out.println("Error reading messages.");
                e.printStackTrace();
            }

            out.write("Connected to server \"main\" as user: " + clientUsername);
            out.newLine();
            out.flush();

            String clientMessage;
            while ((clientMessage = in.readLine()) != null) {

                if (clientMessage.equalsIgnoreCase("q")) break;

                // ----------------------------------------------------------------
                // FILE TRANSFER
                // ----------------------------------------------------------------

                // format: /sendfile <targetUser> <filepath>
                if (clientMessage.startsWith("/sendfile")) {
                    String[] parts = clientMessage.split(" ", 4);
                    if (parts.length == 4) {
                        String targetUser = parts[1];
                        long   fileSize   = Long.parseLong(parts[2]);
                        String filename   = parts[3];

                        ClientThread target = serverMain.getClientThread(targetUser);
                        if (target != null) {
                            serverMain.addPendingTransfer(targetUser, this, filename, fileSize);
                            target.out.write("FILE_INCOMING:" + clientUsername + ":" + filename + ":" + fileSize);
                            target.out.newLine();
                            target.out.flush();
                        } else {
                            out.write("User '" + targetUser + "' not found.");
                            out.newLine();
                            out.flush();
                        }
                    }
                    continue;
                }

                // ----------------------------------------------------------------
                // VIDEO FILE STREAMING
                // ----------------------------------------------------------------

                // format: /stream <targetUser> <filepath>
                if (clientMessage.startsWith("/stream")) {
                    String[] parts = clientMessage.split(" ", 3);
                    if (parts.length == 3) {
                        String targetUser = parts[1];
                        String filename   = parts[2];

                        ClientThread target = serverMain.getClientThread(targetUser);
                        if (target != null) {
                            target.out.write("STREAM_INCOMING:" + clientUsername + ":" + filename);
                            target.out.newLine();
                            target.out.flush();
                        } else {
                            out.write("User '" + targetUser + "' not found.");
                            out.newLine();
                            out.flush();
                        }
                    }
                    continue;
                }

                // receiver accepted the stream — tell sender the receiver's IP and UDP port
                // format: STREAM_ACCEPT:<senderUsername>:<filename>:<udpPort>
                if (clientMessage.startsWith("STREAM_ACCEPT:")) {
                    String[] parts    = clientMessage.split(":", 4);
                    String senderName = parts[1];
                    String filename   = parts[2];
                    String udpPort    = parts[3];

                    String receiverIP = socket.getInetAddress().getHostAddress();

                    ClientThread sender = serverMain.getClientThread(senderName);
                    if (sender != null) {
                        sender.out.write("STREAM_READY:" + filename + ":" + receiverIP + ":" + udpPort);
                        sender.out.newLine();
                        sender.out.flush();
                    }
                    continue;
                }

                // receiver rejected the stream
                if (clientMessage.startsWith("STREAM_REJECT:")) {
                    String senderName = clientMessage.split(":", 2)[1];
                    ClientThread sender = serverMain.getClientThread(senderName);
                    if (sender != null) {
                        sender.out.write("STREAM_REJECTED");
                        sender.out.newLine();
                        sender.out.flush();
                    }
                    continue;
                }

                // ----------------------------------------------------------------
                // P2P WEBCAM VIDEO CALL
                // ----------------------------------------------------------------

                // Caller initiates a call to another user.
                // format: /videocall <targetUser> <callerUdpPort>
                //
                // Flow:
                //   1. Caller sends /videocall to server
                //   2. Server forwards CALL_INCOMING to callee
                //   3. Callee sends CALL_ACCEPT back with their UDP port
                //   4. Server sends CALL_READY  to caller  (callee's IP + port)
                //   5. Server sends CALL_START  to callee  (caller's IP + port)
                //   6. Both clients call startVideoCall() and the webcam streams begin
                if (clientMessage.startsWith("/videocall")) {
                    String[] parts      = clientMessage.split(" ", 3);
                    if (parts.length == 3) {
                        String targetUser   = parts[1];
                        String callerPort   = parts[2]; // UDP port caller is listening on

                        ClientThread target = serverMain.getClientThread(targetUser);
                        if (target != null) {
                            // store caller's UDP port in server so we can pass it to the callee later
                            serverMain.setCallInfo(clientUsername, callerPort);

                            target.out.write("CALL_INCOMING:" + clientUsername + ":" + callerPort);
                            target.out.newLine();
                            target.out.flush();
                        } else {
                            out.write("User '" + targetUser + "' not found.");
                            out.newLine();
                            out.flush();
                        }
                    }
                    continue;
                }

                // Callee accepted — send both sides each other's IP and UDP port so they
                // can start firing webcam frames at each other directly (true P2P).
                // format: CALL_ACCEPT:<callerUsername>:<calleeUdpPort>
                if (clientMessage.startsWith("CALL_ACCEPT:")) {
                    String[] parts      = clientMessage.split(":", 3);
                    String callerName   = parts[1];
                    String calleePort   = parts[2]; // UDP port callee is listening on

                    String calleeIP  = socket.getInetAddress().getHostAddress();
                    String callerPort = serverMain.getCallInfo(callerName);

                    ClientThread caller = serverMain.getClientThread(callerName);
                    if (caller != null && callerPort != null) {
                        String callerIP = caller.socket.getInetAddress().getHostAddress();

                        // tell caller: "callee is ready, here's their IP and port"
                        caller.out.write("CALL_READY:" + calleeIP + ":" + calleePort);
                        caller.out.newLine();
                        caller.out.flush();

                        // tell callee: "caller is ready, here's their IP and port"
                        out.write("CALL_START:" + callerIP + ":" + callerPort);
                        out.newLine();
                        out.flush();

                        serverMain.removeCallInfo(callerName);
                    }
                    continue;
                }

                // Callee rejected the call
                // format: CALL_REJECT:<callerUsername>
                if (clientMessage.startsWith("CALL_REJECT:")) {
                    String callerName = clientMessage.split(":", 2)[1];
                    ClientThread caller = serverMain.getClientThread(callerName);
                    if (caller != null) {
                        caller.out.write("CALL_REJECTED");
                        caller.out.newLine();
                        caller.out.flush();
                    }
                    serverMain.removeCallInfo(callerName);
                    continue;
                }

                // ----------------------------------------------------------------
                // FILE ACCEPT / REJECT
                // ----------------------------------------------------------------

                if (clientMessage.startsWith("FILE_ACCEPT:")) {
                    String[] parts    = clientMessage.split(":", 4);
                    String senderName = parts[1];
                    String filename   = parts[2];

                    ClientThread sender = serverMain.getClientThread(senderName);
                    if (sender != null) {
                        sender.out.write("FILE_READY:" + filename);
                        sender.out.newLine();
                        sender.out.flush();
                    }
                    serverMain.setFileReceiver(senderName, clientUsername);
                    continue;
                }

                if (clientMessage.startsWith("FILE_REJECT:")) {
                    String senderName = clientMessage.split(":", 2)[1];
                    ClientThread sender = serverMain.getClientThread(senderName);
                    if (sender != null) {
                        sender.out.write("FILE_REJECTED");
                        sender.out.newLine();
                        sender.out.flush();
                    }
                    serverMain.removePendingTransfer(clientUsername);
                    continue;
                }

                // ----------------------------------------------------------------
                // CHATROOM COMMANDS (unchanged)
                // ----------------------------------------------------------------

                if (clientMessage.startsWith("/join")) {
                    String[] parts = clientMessage.split(" ", 2);
                    if (parts.length < 2) {
                        out.write("Usage: /join <serverName>");
                        out.newLine();
                        out.flush();
                        continue;
                    }

                    String serverName = parts[1];
                    HashMap<String, List<ClientThread>> servers = serverMain.getServers();
                    if (servers.containsKey(serverName)) {
                        serverMain.removeFromChat(serverIn, this);
                        serverIn = serverName;
                        serverMain.addToChat(serverName, this);
                        try {
                            out.write("Server Joined: " + serverIn + "\n");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        // Test read a txt file:
                        File NewServertest = new File("discord-system-313/src/ServerLogs/" + serverIn + ".txt");

                        // Create file if it doesn't exist
                        try {
                            if (test.createNewFile()) {
                                System.out.println("File created: " + test.getName());
                            }
                        } catch (IOException e) {
                            System.out.println("Error creating file.");
                            e.printStackTrace();
                        }   // This is my test file obviously

                        try (Scanner myReader = new Scanner(NewServertest)) {
                            synchronized (clientThread) {
//                    if (!ServerLog) {
//                        ServerLog = true;

                                // Read all texts
                                try (Scanner myServerReader = new Scanner(NewServertest)) {
                                    while (myServerReader.hasNextLine()) {
                                        String text = myServerReader.nextLine();
                                        System.out.println(text);               // This is why it's read constantly
                                    }
                                } catch (IOException e) {
                                    System.out.println("Error reading messages, ensure the log actually exists at the specified location.");
                                    e.printStackTrace();
                                }
//                    }
                            }

                            while (myReader.hasNextLine()) {
                                String text = myReader.nextLine();
                                out.write(text);
                                out.newLine();
                            }
                            out.flush();
                        } catch (IOException e) {
                            System.out.println("Error reading messages, ensure the log actually exists at the specified location.");
                            e.printStackTrace();
                        }
                    } else {
                        out.write("Chat Room " + serverName + " not found");
                        out.newLine();
                        out.flush();
                    }
                    continue;
                }

                if (clientMessage.startsWith("/create")) {
                    String[] parts = clientMessage.split(" ", 2);
                    if (parts.length < 2) {
                        out.write("Usage: /create <serverName>");
                        out.newLine();
                        out.flush();
                        continue;
                    }
                    String newServer = parts[1];
                    serverMain.removeFromChat(serverIn, this);
                    serverIn = newServer;
                    serverMain.addToChat(newServer, this);
                    out.write("Server Created: " + serverIn);
                    out.newLine();
                    out.flush();
                    out.write("Server Joined: " + serverIn);
                    out.newLine();
                    out.flush();

                    File NewServertest = new File("discord-system-313/src/ServerLogs/" + serverIn + ".txt");
                    try {
                        if (NewServertest.createNewFile()) {
                            System.out.println("File created: " + NewServertest.getName());
                        }
                    } catch (IOException e) {
                        System.out.println("Error creating file.");
                        e.printStackTrace();
                    }
                    try (Scanner myReader = new Scanner(NewServertest)) {
                        synchronized (clientThread) {

                                // Read all texts
                                try (Scanner myServerReader = new Scanner(NewServertest)) {
                                    while (myServerReader.hasNextLine()) {
                                        System.out.println(myServerReader.nextLine());
                                    }
                                } catch (IOException e) {
                                    System.out.println("Error reading messages.");
                                    e.printStackTrace();
                                }
                        }
                        while (myReader.hasNextLine()) {
                            out.write(myReader.nextLine());
                            out.newLine();
                        }
                        out.flush();
                    } catch (IOException e) {
                        System.out.println("Error reading messages.");
                        e.printStackTrace();
                    }
                    continue;
                }

                if (clientMessage.equals("/status")) {
                    out.write("Current Chatroom: " + serverIn);
                    out.newLine();
                    out.flush();
                    continue;
                }
                if (clientMessage.equals("/online")) {
                    out.write("Number of users online: " + onlineCounter.getOnlineCount());
                    out.newLine();
                    out.flush();
                    continue;
                }
                if (clientMessage.equals("/chatrooms")) {
                    HashMap<String, List<ClientThread>> servers = serverMain.getServers();
                    out.write("Chat Rooms " + servers.keySet());
                    out.newLine();
                    out.flush();
                    continue;
                }
                if (clientMessage.equals("/clients")) {
                    clientsLock.lock();
                    try {
                        HashMap<String, Integer> clients = serverMain.getClients();
                        out.write("Clients: " + clients.keySet());
                        out.newLine();
                        out.flush();
                    } finally {
                        clientsLock.unlock();
                    }
                    continue;
                }
                if (clientMessage.startsWith("/changeUsername")) {
                    String[] parts = clientMessage.split(" ", 2);
                    if (parts.length < 2) {
                        out.write("Usage: /changeUsername <newUsername>");
                        out.newLine();
                        out.flush();
                        continue;
                    }
                    String oldUsername = clientUsername;
                    String newUsername = parts[1];
                    clientsLock.lock();
                    try {
                        clientUsername = newUsername;
                        serverMain.removeClient(oldUsername);
                        serverMain.addClientToServer(newUsername, serverNumber);
                    } finally {
                        clientsLock.unlock();
                    }
                    out.write("Username successfully changed to " + newUsername);
                    out.newLine();
                    out.flush();
                    broadCastMessage(oldUsername + " changed name to " + newUsername);
                    continue;
                }

                // ---- Normal chat message ----
                System.out.println(clientUsername + ": " + clientMessage);
                if (!clientMessage.contains("/create") && !clientMessage.contains("/join")) {
                    FileWriter messagetoSend = new FileWriter("discord-system-313/src/ServerLogs/" + serverIn + ".txt", true);
                    messagetoSend.write(clientUsername + ": " + clientMessage);
                    messagetoSend.write("\n");
                    messagetoSend.close();
                }
                broadCastMessage(clientMessage);
            }

        } catch (IOException e) {
            // client disconnected
        } finally {
            closeClientThread(socket, in, out);
        }
    }

    public void closeClientThread(Socket socket, BufferedReader bufferedReader, BufferedWriter bufferedWriter) {
        if (closed) return;
        closed = true;
        synchronized (clientThread) {
            clientThread.remove(this);
        }
        serverMain.removeClient(clientUsername);
        System.out.println(clientUsername + " has disconnected from server.");
        broadCastMessage(" has left the server");
        onlineCounter.decrementOnline();
        try {
            if (socket != null && !socket.isClosed()) socket.close();
            if (bufferedReader != null) bufferedReader.close();
            if (bufferedWriter != null) bufferedWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}