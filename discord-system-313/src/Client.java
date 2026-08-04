import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Scanner;
import javafx.application.Application;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.scene.image.Image;
import javafx.geometry.Insets;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.application.Platform;
import javafx.scene.control.ScrollPane;
import javafx.util.Duration;

import java.awt.Desktop;


import static java.lang.System.exit;

public class Client extends Application {
    Stage window;
    Scene loginScene, mainScene;
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private String username;
    private String pendingFilePath; // the file we want to send
    private boolean connected;
    private VBox msgBox;
    private ScrollPane msgDisplay;
    private TextField txtInput;


    private int udpReceivePort = 0; // 0 = let the OS pick a free port


    private void assignUdpPort() {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            udpReceivePort = probe.getLocalPort();
            System.out.println("[UDP] Assigned receive port: " + udpReceivePort);
        } catch (IOException e) {
            udpReceivePort = 6000; // last-resort fallback
            System.out.println("[UDP] Could not probe free port, falling back to 6000");
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    public void connectClient(Socket socket, String username) {
        try {
            this.socket = socket;
            System.out.println("Connected to server.");
            this.connected = true;

            this.in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            this.username = username;

            out.write(username);
            out.newLine();
            out.flush();

        } catch (IOException e) {
            closeClient(socket, in, out);
        }
    }

    private void addMessage(String message) {
        Platform.runLater(() -> {
            Label msgLabel = new Label(message);
            msgLabel.setWrapText(true);
            msgLabel.setMaxWidth(Double.MAX_VALUE);
            msgBox.getChildren().add(msgLabel);
            msgDisplay.layout();
        });
    }

    // adds an image inline in the chat after it is received, like Discord
    private void addImageToChat(String filename, File imageFile) {
        Platform.runLater(() -> {
            Label nameLabel = new Label(filename);
            nameLabel.setWrapText(true);

            Image img = new Image(imageFile.toURI().toString());
            ImageView imageView = new ImageView(img);
            imageView.setFitWidth(300);
            imageView.setFitHeight(300);
            imageView.setPreserveRatio(true);

            VBox imgBox = new VBox(5, nameLabel, imageView);
            imgBox.setPadding(new Insets(5));
            msgBox.getChildren().add(imgBox);
            msgDisplay.layout();
        });
    }

    // adds a video inline in the chat after it is received, like Discord
    private void addVideoToChat(String filename, File videoFile) {
        Platform.runLater(() -> {
            Label nameLabel = new Label(filename);      // name of file is displayed above video
            nameLabel.setWrapText(true);

            // need to add the video stuff like this for java fx
            Media video = new Media(videoFile.toURI().toString());
            MediaPlayer mdplayer = new MediaPlayer(video);
            MediaView mediaView = new MediaView(mdplayer);

            // setting height of the video
            mediaView.setFitHeight(485);
            mediaView.setFitWidth(530);
            mediaView.setPreserveRatio(true);

            // basic video controls, note that I cant be bothered making a progress bar
            Button resume = new Button("Play");
            Button pause = new Button("Pause");
            Button restart = new Button("Restart");
            HBox controls = new HBox(20, pause, resume, restart);

            // function to handle play, pause and restart buttons
            resume.setOnAction(a -> mdplayer.play());
            pause.setOnAction(a -> mdplayer.pause());
            restart.setOnAction(a -> mdplayer.seek(Duration.ZERO));

            VBox vidBox = new VBox(5, nameLabel, mediaView);
            vidBox.setPadding(new Insets(5));
            msgBox.getChildren().addAll(vidBox, controls);       // add the video and controls to the messaging interface
            msgDisplay.layout();
        });
    }

    // checks file extension and opens the right viewer, gets displayed in the actual messaging chat
    private void openReceivedFile(String filename, File file) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")) { // is an image file or gif
            addImageToChat(filename, file);
        } else if(lower.endsWith(".mp4") || lower.endsWith(".flv")){        // is a video file, these are formats javafx supports
            addVideoToChat(filename, file);
        }
        // other file types just save silently
    }

    private void sendVideoStreamUDP(String filename, String receiverIP, int receiverPort) {
        new Thread(() -> {
            try {
                InetAddress receiverAddress = InetAddress.getByName(receiverIP);
                addMessage("[STREAM] Streaming " + filename + "...");
                System.out.println("[STREAM DEBUG] Sender: firing UDP to " + receiverIP + ":" + receiverPort);

                ProcessBuilder ffmpegPB = new ProcessBuilder(
                        "ffmpeg",
                        "-re",                // read at native framerate — real-time pacing
                        "-i", pendingFilePath,
                        "-c", "copy",         // copy streams, no re-encoding (fast)
                        "-f", "mpegts",       // MPEG-TS output format (streaming-friendly)
                        "pipe:1"              // write to stdout
                );
                ffmpegPB.redirectErrorStream(false);
                Process ffmpegProcess = ffmpegPB.start();

                // drain ffmpeg stderr silently so it never blocks
                new Thread(() -> {
                    try { ffmpegProcess.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
                    catch (IOException ignored) {}
                }).start();

                try (DatagramSocket udpSocket = new DatagramSocket();
                     InputStream ffmpegOut = ffmpegProcess.getInputStream()) {

                    byte[] buffer = new byte[60000];
                    int bytesRead;

                    while ((bytesRead = ffmpegOut.read(buffer)) != -1) {
                        byte[] packet = new byte[bytesRead + 4];
                        packet[0] = (byte) (bytesRead >> 24);
                        packet[1] = (byte) (bytesRead >> 16);
                        packet[2] = (byte) (bytesRead >>  8);
                        packet[3] = (byte)  bytesRead;
                        System.arraycopy(buffer, 0, packet, 4, bytesRead);

                        DatagramPacket dgPacket = new DatagramPacket(
                                packet, packet.length, receiverAddress, receiverPort);
                        udpSocket.send(dgPacket);
                        // no sleep needed — ffmpeg -re already paces at real-time
                    }

                    byte[] endPacket = {-1, -1, -1, -1};
                    udpSocket.send(new DatagramPacket(endPacket, 4, receiverAddress, receiverPort));
                }

                ffmpegProcess.waitFor();
                System.out.println("[STREAM] UDP stream finished: " + filename);
                addMessage("[STREAM] Stream finished: " + filename);

            } catch (Exception e) {
                System.out.println("UDP stream send error: " + e.getMessage());
                addMessage("[STREAM] Send error: " + e.getMessage());
            }
        }).start();
    }

    private void receiveVideoStreamUDP(String filename) {
        new Thread(() -> {
            Process playerProcess = null;
            OutputStream playerStdin = null;

            try (DatagramSocket udpSocket = new DatagramSocket(udpReceivePort)) {
                System.out.println("[STREAM DEBUG] Receiver: bound UDP socket on port " + udpReceivePort);

                String[][] candidates = {
                        {"ffplay", "-autoexit", "-f", "mpegts", "-i", "pipe:0"},
                        {"C:\\ffmpeg\\bin\\ffplay.exe", "-autoexit", "-f", "mpegts", "-i", "pipe:0"},
                        {"vlc", "--intf", "dummy", "-"},
                        {"C:\\Program Files\\VideoLAN\\VLC\\vlc.exe", "--intf", "dummy", "-"}
                };

                ProcessBuilder pb = null;
                for (String[] cmd : candidates) {
                    try {
                        ProcessBuilder test = new ProcessBuilder(cmd);
                        test.redirectErrorStream(true);
                        playerProcess = test.start();
                        pb = test;
                        System.out.println("[STREAM] Launched player: " + cmd[0]);
                        break;
                    } catch (IOException ignored) {
                        // try next candidate
                    }
                }

                if (playerProcess == null) {
                    addMessage("[STREAM] ERROR: No video player found.\n" +
                            "Install ffmpeg (https://ffmpeg.org/download.html) and add it to PATH,\n" +
                            "or install VLC. Then restart the app.");
                    return;
                }

                playerStdin = playerProcess.getOutputStream();
                addMessage("[STREAM] Receiving stream — player is live!");
                System.out.println("[STREAM DEBUG] Player process alive: " + playerProcess.isAlive());

                final Process proc = playerProcess;
                new Thread(() -> {
                    try {
                        java.io.BufferedReader pr = new java.io.BufferedReader(
                                new java.io.InputStreamReader(proc.getInputStream()));
                        String line;
                        while ((line = pr.readLine()) != null) {
                            System.out.println("[FFPLAY] " + line);
                        }
                    } catch (IOException ignored) {}
                }).start();

                boolean firstPacket = true;

                byte[] buf = new byte[60004]; // 60KB payload + 4-byte header
                DatagramPacket packet = new DatagramPacket(buf, buf.length);

                while (true) {
                    udpSocket.receive(packet);
                    byte[] data = packet.getData();

                    // decode 4-byte big-endian length
                    int chunkSize = ((data[0] & 0xFF) << 24) |
                            ((data[1] & 0xFF) << 16) |
                            ((data[2] & 0xFF) <<  8) |
                            (data[3] & 0xFF);

                    if (chunkSize == -1) break; // end-of-stream sentinel

                    if (firstPacket) {
                        System.out.println("[STREAM DEBUG] First packet received! chunkSize=" + chunkSize);
                        firstPacket = false;
                    }

                    // write raw video bytes to player stdin — this IS the stream
                    playerStdin.write(data, 4, chunkSize);
                    playerStdin.flush();
                }

                System.out.println("[STREAM] Stream fully received: " + filename);
                addMessage("[STREAM] Stream complete: " + filename);

            } catch (IOException e) {
                System.out.println("UDP stream receive error: " + e.getMessage());
                addMessage("[STREAM] Stream error: " + e.getMessage());
            } finally {
                if (playerStdin != null) {
                    try { playerStdin.close(); } catch (IOException ignored) {}
                }
            }
        }).start();
    }

    private void sendFileData(String filename) {
        new Thread(() -> {
            try (Socket fileSocket = new Socket("localhost", 5002)) {
                DataOutputStream dos = new DataOutputStream(fileSocket.getOutputStream());

                dos.writeUTF("SEND");
                dos.writeUTF(username);

                File file = new File(pendingFilePath);
                dos.writeLong(file.length());

                byte[] buffer = new byte[8192];
                int bytesRead;
                try (FileInputStream fis = new FileInputStream(file)) {
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        dos.write(buffer, 0, bytesRead);
                    }
                }
                dos.flush();
                System.out.println("[FILE] Upload complete: " + filename);
                addMessage("[FILE] Upload complete: " + filename);

            } catch (IOException e) {
                System.out.println("File send error: " + e.getMessage());
                addMessage("[FILE] File send error: " + e.getMessage());
            }
        }).start();
    }

    private void receiveFile(String filename, long size) {
        new Thread(() -> {
            try (Socket fileSocket = new Socket("localhost", 5002)) {
                DataOutputStream dos = new DataOutputStream(fileSocket.getOutputStream());
                DataInputStream  dis = new DataInputStream(fileSocket.getInputStream());

                dos.writeUTF("RECEIVE");
                dos.writeUTF(username);
                dos.flush();

                String home = System.getProperty("user.home");
                File saveFile = new File(home+"/Downloads/" + filename);

                try (FileOutputStream fos = new FileOutputStream(saveFile, false)) {        // false is so file gets overwritten if it already exists
                    byte[] buffer    = new byte[8192];
                    long   remaining = size;
                    int    bytesRead;
                    while (remaining > 0 &&
                            (bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        remaining -= bytesRead;
                    }
                }
                System.out.println("[FILE] Saved: " + saveFile.getAbsolutePath());
                addMessage("[FILE] Saved: " + saveFile.getAbsolutePath());
                openReceivedFile(filename, saveFile);

            } catch (IOException e) {
                System.out.println("File receive error: " + e.getMessage());
                addMessage("File receive error: " + e.getMessage());
            }
        }).start();
    }

    public void listenForMessages() {
        new Thread(() -> {
            String messageFromServer;
            try {
                while ((messageFromServer = in.readLine()) != null) {

                    if (messageFromServer.startsWith("FILE_INCOMING:")) {
                        String[] parts  = messageFromServer.split(":", 4);
                        String from     = parts[1];
                        String filename = parts[2];
                        long   size     = Long.parseLong(parts[3]);

                        Platform.runLater(() -> {
                            Alert alert = new Alert(AlertType.CONFIRMATION);
                            alert.setTitle("Incoming File");
                            alert.setHeaderText(from + " wants to send you a file");
                            alert.setContentText("File: " + filename + "\nSize: " + size + " bytes\n\nAccept?");

                            ButtonType acceptBtn = new ButtonType("Accept");
                            ButtonType rejectBtn = new ButtonType("Reject");
                            alert.getButtonTypes().setAll(acceptBtn, rejectBtn);

                            Optional<ButtonType> result = alert.showAndWait();
                            try {
                                if (result.isPresent() && result.get() == acceptBtn) {
                                    out.write("FILE_ACCEPT:" + from + ":" + filename + ":" + size);
                                    out.newLine();
                                    out.flush();
                                    receiveFile(filename, size);
                                } else {
                                    out.write("FILE_REJECT:" + from);
                                    out.newLine();
                                    out.flush();
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                        continue;
                    }

                    if (messageFromServer.startsWith("STREAM_INCOMING:")) {
                        String[] parts  = messageFromServer.split(":", 3);
                        String from     = parts[1];
                        String filename = parts[2];

                        Platform.runLater(() -> {
                            Alert alert = new Alert(AlertType.CONFIRMATION);
                            alert.setTitle("Incoming Stream");
                            alert.setHeaderText(from + " wants to stream a video to you");
                            alert.setContentText("File: " + filename + "\n\nAccept?\n\n(Requires ffplay or VLC on your PATH)");

                            ButtonType acceptBtn = new ButtonType("Accept");
                            ButtonType rejectBtn = new ButtonType("Reject");
                            alert.getButtonTypes().setAll(acceptBtn, rejectBtn);

                            Optional<ButtonType> result = alert.showAndWait();
                            try {
                                if (result.isPresent() && result.get() == acceptBtn) {
                                    out.write("STREAM_ACCEPT:" + from + ":" + filename + ":" + udpReceivePort);
                                    out.newLine();
                                    out.flush();
                                    receiveVideoStreamUDP(filename);
                                } else {
                                    out.write("STREAM_REJECT:" + from);
                                    out.newLine();
                                    out.flush();
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                        continue;
                    }

                    if (messageFromServer.startsWith("FILE_READY:")) {
                        String filename = messageFromServer.split(":", 2)[1];
                        sendFileData(filename);
                        continue;
                    }

                    if (messageFromServer.equals("FILE_REJECTED")) {
                        addMessage("[FILE] Transfer was rejected.");
                        continue;
                    }

                    if (messageFromServer.startsWith("STREAM_READY:")) {
                        String[] parts      = messageFromServer.split(":", 4);
                        String filename     = parts[1];
                        String receiverIP   = parts[2];
                        int    receiverPort = Integer.parseInt(parts[3]);
                        sendVideoStreamUDP(filename, receiverIP, receiverPort);
                        continue;
                    }

                    if (messageFromServer.equals("STREAM_REJECTED")) {
                        addMessage("[STREAM] Stream was rejected.");
                        continue;
                    }

                    if (messageFromServer.equalsIgnoreCase("q")) {
                        closeClient(socket, in, out);
                        break;
                    }

                    System.out.println("FROM SERVER: " + messageFromServer);
                    String finalMsg = messageFromServer;
                    Platform.runLater(() -> addMessage(finalMsg));
                }

                System.out.println("Disconnected from server.");
                closeClient(socket, in, out);

            } catch (IOException e) {
                closeClient(socket, in, out);
            }
        }).start();
    }

    public void closeClient(Socket socket, BufferedReader bufferedReader, BufferedWriter bufferedWriter) {
        try {
            if (bufferedReader != null) bufferedReader.close();
            if (bufferedWriter != null) bufferedWriter.close();
            if (socket != null)         socket.close();

            // remove username from usernames in use file when that user disconnects
            File file = new File("discord-system-313/src/namesInUse.txt");
            String content = new String(Files.readAllBytes(file.toPath()));
            content = content.replace(username + "\n", "");     // somewhat stops removing incorrect usernames
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes());
            fos.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
        exit(0);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        window = primaryStage;

        Label welcomeLabel = new Label("Welcome");
        Image userImage = new Image("Images/user.png");
        ImageView userImageView = new ImageView(userImage);
        Label loginLabel = new Label("Username: ");
        TextField loginField = new TextField();
        Label errorLabel = new Label();

        Button logOutButton = new Button("Log Out");
        Button loginButton = new Button("Login");
        loginButton.setOnAction(e -> {
            String inputName = loginField.getText().trim();
            BufferedReader reader = null;
            ArrayList<String> names;
            try {
                // file is for making sure no users with the same name are logged in at the same time as that causes issues
                reader = new BufferedReader(new FileReader("discord-system-313/src/namesInUse.txt"));
                String line = reader.readLine();
                names = new ArrayList<>();  // create an array of usernames in use
                while (line != null) {
                    names.add(line);
                    line = reader.readLine();
                }
            } catch (FileNotFoundException ex) {
                throw new RuntimeException(ex);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

            if (inputName.isBlank() || inputName.startsWith("/") || inputName.equals("q")) {
                System.out.println("Invalid username, must include characters");
                errorLabel.setText("Invalid username, try again");
            } else if (names.contains(inputName)) {
                System.out.println("Invalid username");
                errorLabel.setText("Username already in use, try again");
            } else {
                try {
                    // add username to usernames currently in use file so no one else of that username can log in
                    FileWriter newOnlineUser = new FileWriter("discord-system-313/src/namesInUse.txt", true);
                    newOnlineUser.write(inputName);
                    newOnlineUser.write("\n");
                    newOnlineUser.close();

                    // grab a free UDP port for this client instance before connecting
                    assignUdpPort();

                    // Change "localhost" to ip address of machine running server to have different machines able to connect
                    Socket socket = new Socket("localhost", 5001);
                    connectClient(socket, inputName);
                    listenForMessages();

                    window.setScene(mainScene);
                    window.setTitle("Chat");
                    window.setResizable(true);
                    window.setMaximized(true);

                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        });

        msgBox = new VBox(10);
        msgDisplay = new ScrollPane(msgBox);
        msgDisplay.setFitToWidth(true);
        msgDisplay.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        msgDisplay.vvalueProperty().bind(msgBox.heightProperty());

        txtInput = new TextField();

        txtInput.setOnAction(e -> {
            String message = txtInput.getText().trim();

            try {
                if(message.startsWith("/changeUsername")) {
                    String[] parts = message.split(" ", 2);
                    if (parts.length == 2) {
                        this.username = parts[1];
                    }
                }

                    if ((message.startsWith("/create")) || (message.startsWith("/join"))) {
                        String[] parts = message.split(" ", 2);
                        if (parts.length == 2) {
                            msgBox.getChildren().clear();       // This wipes the box every time to clear it of previous texts
                                                                // Create and join bring text back
                        }

                    }

                    if (message.startsWith("/sendfile")) {
                    String[] parts = message.split(" ", 3);
                    if (parts.length == 3) {
                        String targetUser = parts[1];
                        String filePath   = parts[2];
                        File   file       = new File(filePath);
                        if (!file.exists()) {
                            System.out.println("[FILE] File not found: " + filePath);
                            addMessage("[FILE] File not found: " + filePath);
                            txtInput.clear();
                            return;
                        }
                        this.pendingFilePath = filePath;
                        out.write("/sendfile " + targetUser + " " + file.length() + " " + file.getName());
                        out.newLine();
                        out.flush();
                        addMessage("[FILE] Waiting for " + targetUser + " to accept...");
                    } else {
                        System.out.println("Usage: /sendfile <username> <filepath>");
                        addMessage("Usage: /sendfile <username> <filepath>");
                    }
                    txtInput.clear();
                    return;
                }

                if (message.startsWith("/stream")) {
                    String[] parts = message.split(" ", 3);
                    if (parts.length == 3) {
                        String targetUser = parts[1];
                        String filePath   = parts[2];
                        File   file       = new File(filePath);
                        if (!file.exists()) {
                            System.out.println("[STREAM] File not found: " + filePath);
                            addMessage("[STREAM] File not found: " + filePath);
                            txtInput.clear();
                            return;
                        }
                        this.pendingFilePath = filePath;
                        out.write("/stream " + targetUser + " " + file.getName());
                        out.newLine();
                        out.flush();
                        addMessage("[STREAM] Waiting for " + targetUser + " to accept...");
                    } else {
                        System.out.println("Usage: /stream <username> <filepath>");
                        addMessage("Usage: /stream <username> <filepath>");
                    }
                    txtInput.clear();
                    return;
                }

                out.write(message);
                out.newLine();
                out.flush();

                if (!message.isEmpty()) {
                    Platform.runLater(() -> addMessage(username + ": " + message));
                }

                txtInput.clear();

            } catch (IOException ex) {
                ex.printStackTrace();
            }
        });

        VBox loginFieldHolder = new VBox(5);
        loginFieldHolder.getChildren().addAll(loginLabel, loginField, errorLabel);
        VBox loginVBox = new VBox(20);
        loginVBox.getChildren().addAll(welcomeLabel, userImageView, loginFieldHolder, loginButton);
        BorderPane loginBorderPane = new BorderPane();
        loginBorderPane.setCenter(loginVBox);
        BorderPane backgroundPane = new BorderPane();
        backgroundPane.setCenter(loginBorderPane);
        loginScene = new Scene(backgroundPane, 800, 600);

        loginScene.getStylesheets().add("styles.css");
        loginBorderPane.getStyleClass().add("loginBorderPane");
        loginLabel.getStyleClass().add("loginLabels");
        errorLabel.getStyleClass().add("loginLabels");
        backgroundPane.getStyleClass().add("backgroundPane");
        loginButton.getStyleClass().add("loginButton");
        loginField.getStyleClass().add("loginField");
        welcomeLabel.getStyleClass().add("welcomeLabel");
        userImageView.setFitWidth(150);
        userImageView.setFitHeight(150);
        userImageView.setPreserveRatio(true);
        userImageView.setSmooth(true);
        userImageView.setCache(true);
        loginField.setMaxWidth(350);
        loginFieldHolder.setAlignment(Pos.CENTER);
        loginVBox.setAlignment(Pos.CENTER);
        loginBorderPane.setMaxWidth(600);
        loginBorderPane.setMaxHeight(450);
        loginButton.setMaxWidth(110);

        VBox gcButtonVBox = new VBox(10);
        HBox titleBox = new HBox(10);
        BorderPane chatContainer = new BorderPane();
        BorderPane mainViewBackgroundPane = new BorderPane();

        mainViewBackgroundPane.setCenter(chatContainer);
        mainViewBackgroundPane.setLeft(gcButtonVBox);
        mainViewBackgroundPane.setTop(titleBox);

        chatContainer.setBottom(txtInput);
        chatContainer.setCenter(msgDisplay);
        mainScene = new Scene(mainViewBackgroundPane, 600, 600);

        mainScene.getStylesheets().add("styles.css");
        mainViewBackgroundPane.getStyleClass().add("mainViewBackgroundPane");
        gcButtonVBox.getStyleClass().add("gcButtonVBox");
        logOutButton.getStyleClass().add("loginButton");
        gcButtonVBox.getChildren().add(logOutButton);
        titleBox.getStyleClass().add("titleBox");
        chatContainer.getStyleClass().add("chatContainer");
        txtInput.getStyleClass().add("txtInput");
        msgDisplay.getStyleClass().add("msgDisplay");

        // logs user out
        logOutButton.setOnAction(e -> {
            try {
                out.write("q");
                out.newLine();
                out.flush();
                closeClient(socket, in, out);
            } catch (IOException ex) {
                closeClient(socket, in, out);
            }
        });

        mainViewBackgroundPane.setPadding(new Insets(10));
        gcButtonVBox.setPadding(new Insets(10));
        titleBox.setPadding(new Insets(10));
        chatContainer.setPadding(new Insets(10));

        gcButtonVBox.setAlignment(Pos.CENTER);
        gcButtonVBox.setPrefWidth(180);
        titleBox.setPrefHeight(100);

        msgDisplay.setPrefHeight(Region.USE_COMPUTED_SIZE);
        msgDisplay.setPrefWidth(Region.USE_COMPUTED_SIZE);
        msgBox.setPrefWidth(Region.USE_COMPUTED_SIZE);

        window.setScene(loginScene);
        window.setTitle("Login");
        window.setResizable(false);
        window.show();
    }
}