// Save this entire file as MediaUploadService.java in a single directory

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

// ================ VideoMessage class ================
class VideoMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final UUID id;
    private final String filename;
    private final byte[] data;
    private final long timestamp;
    private final String hash;
    
    public VideoMessage(String filename, byte[] data) {
        this.id = UUID.randomUUID();
        this.filename = filename;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
        this.hash = generateHash(data);
    }
    
    public UUID getId() {
        return id;
    }
    
    public String getFilename() {
        return filename;
    }
    
    public byte[] getData() {
        return data;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public String getHash() {
        return hash;
    }
    
    private String generateHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return "hash-error-" + UUID.randomUUID().toString();
        }
    }
}

// ================ ConfigLoader class ================
class ConfigLoader {
    
    public static Map<String, Integer> loadConfig(String configPath) throws IOException {
        Map<String, Integer> config = new HashMap<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(configPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("//")) {
                    continue;
                }
                
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    int value = Integer.parseInt(parts[1].trim());
                    config.put(key, value);
                }
            }
        }
        
        return config;
    }
}

// ================ ProducerApp class ================
class ProducerApp extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8888;
    
    private JButton selectFileButton;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JLabel connectionStatusLabel;
    
    private ExecutorService producerThreadPool;
    private ScheduledExecutorService connectionChecker;
    private boolean isServerConnected = false;
    
    public ProducerApp(int producerThreads) {
        setTitle("Video Producer");
        setSize(600, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Initialize thread pool
        producerThreadPool = Executors.newFixedThreadPool(producerThreads);
        connectionChecker = Executors.newSingleThreadScheduledExecutor();
        
        // Set up UI
        JPanel mainPanel = new JPanel(new BorderLayout());
        
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        selectFileButton = new JButton("Select Video File");
        statusLabel = new JLabel("Ready to upload");
        connectionStatusLabel = new JLabel("Checking server connection...");
        connectionStatusLabel.setForeground(Color.ORANGE);
        
        topPanel.add(selectFileButton);
        topPanel.add(statusLabel);
        topPanel.add(connectionStatusLabel);
        
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        
        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Add action listener to button
        selectFileButton.addActionListener(this::selectAndUploadFile);
        selectFileButton.setEnabled(false); // Disabled until server connection is confirmed
        
        add(mainPanel);
        setLocationRelativeTo(null);
        setVisible(true);
        
        log("Producer started with " + producerThreads + " upload threads");
        
        // Start periodic connection check
        startConnectionChecker();
    }
    
    private void startConnectionChecker() {
        connectionChecker.scheduleAtFixedRate(() -> {
            boolean connected = checkServerConnection();
            if (connected != isServerConnected) {
                isServerConnected = connected;
                SwingUtilities.invokeLater(() -> {
                    if (isServerConnected) {
                        connectionStatusLabel.setText("Connected to server");
                        connectionStatusLabel.setForeground(Color.GREEN);
                        selectFileButton.setEnabled(true);
                        log("Connected to server");
                    } else {
                        connectionStatusLabel.setText("Server not available");
                        connectionStatusLabel.setForeground(Color.RED);
                        selectFileButton.setEnabled(false);
                        log("Lost connection to server");
                    }
                });
            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    
    private boolean checkServerConnection() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    private void selectAndUploadFile(ActionEvent e) {
        if (!isServerConnected) {
            JOptionPane.showMessageDialog(this, 
                "Server is not available. Please try again later.",
                "Connection Error", 
                JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        JFileChooser fileChooser = new JFileChooser();
        int result = fileChooser.showOpenDialog(this);
        
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            uploadFile(selectedFile);
        }
    }
    
    private void uploadFile(File file) {
        producerThreadPool.submit(() -> {
            try {
                log("Reading file: " + file.getName());
                SwingUtilities.invokeLater(() -> statusLabel.setText("Uploading: " + file.getName()));
                
                // Read file into byte array
                byte[] fileData = Files.readAllBytes(file.toPath());
                
                // Create video message
                VideoMessage videoMessage = new VideoMessage(file.getName(), fileData);
                
                // Connect to server and send video
                log("Connecting to server...");
                try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
                     ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                     ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {
                    
                    log("Sending video: " + file.getName() + " (" + fileData.length / 1024 + " KB)");
                    oos.writeObject(videoMessage);
                    oos.flush();
                    
                    // Receive response from server
                    String response = (String) ois.readObject();
                    
                    if (response.startsWith("SUCCESS")) {
                        log("Video sent successfully: " + file.getName());
                        SwingUtilities.invokeLater(() -> statusLabel.setText("Upload complete: " + file.getName()));
                    } else if (response.startsWith("DUPLICATE")) {
                        log("Duplicate file detected: " + file.getName());
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText("Duplicate file not uploaded: " + file.getName());
                            JOptionPane.showMessageDialog(null,
                                "This file has already been uploaded.",
                                "Duplicate File",
                                JOptionPane.WARNING_MESSAGE);
                        });
                    } else {
                        log("Upload failed: " + response);
                        SwingUtilities.invokeLater(() -> statusLabel.setText("Upload failed"));
                    }
                }
                
            } catch (IOException | ClassNotFoundException ex) {
                log("Error uploading file: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> statusLabel.setText("Upload failed"));
            }
        });
    }
    
    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + System.currentTimeMillis() + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
    
    public void shutdown() {
        connectionChecker.shutdown();
        producerThreadPool.shutdown();
    }
}

// ================ ConsumerApp class ================
class ConsumerApp extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final int SERVER_PORT = 8888;
    private static final String UPLOAD_DIR = "uploads";
    
    private JPanel videoListPanel;
    private JLabel statusLabel;
    private JTextArea logArea;
    
    private BlockingQueue<VideoMessage> videoQueue;
    private ExecutorService consumerThreadPool;
    private ExecutorService serverThreadPool;
    private ServerSocket serverSocket;
    
    // Add a set to track already uploaded file hashes
    private Set<String> fileHashes = Collections.synchronizedSet(new HashSet<>());
    
    public ConsumerApp(int consumerThreads, int queueSize) {
        setTitle("Video Consumer");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Initialize queue and thread pools
        videoQueue = new ArrayBlockingQueue<>(queueSize);
        consumerThreadPool = Executors.newFixedThreadPool(consumerThreads);
        serverThreadPool = Executors.newSingleThreadExecutor();
        
        // Create uploads directory if it doesn't exist
        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }
        
        // Set up UI
        JPanel mainPanel = new JPanel(new BorderLayout());
        
        JPanel topPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Server running. Queue capacity: " + queueSize);
        topPanel.add(statusLabel, BorderLayout.WEST);
        
        JScrollPane videoScrollPane = new JScrollPane();
        videoListPanel = new JPanel();
        videoListPanel.setLayout(new BoxLayout(videoListPanel, BoxLayout.Y_AXIS));
        videoScrollPane.setViewportView(videoListPanel);
        
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setPreferredSize(new Dimension(800, 150));
        
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, videoScrollPane, logScrollPane);
        splitPane.setResizeWeight(0.7);
        
        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(splitPane, BorderLayout.CENTER);
        
        add(mainPanel);
        setLocationRelativeTo(null);
        setVisible(true);
        
        // Start server to accept connections
        if (startServer()) {
            // Start consumer threads
            for (int i = 0; i < consumerThreads; i++) {
                final int threadId = i;
                consumerThreadPool.submit(() -> processVideoQueue(threadId));
            }
            
            log("Consumer started with " + consumerThreads + " processing threads and queue size " + queueSize);
        } else {
            statusLabel.setText("Failed to start server");
            statusLabel.setForeground(Color.RED);
        }
    }
    
    private boolean startServer() {
        try {
            serverSocket = new ServerSocket(SERVER_PORT);
            log("Server listening on port " + SERVER_PORT);
            
            serverThreadPool.submit(() -> {
                while (!Thread.currentThread().isInterrupted() && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        log("Client connected: " + clientSocket.getInetAddress());
                        
                        // Handle client in a separate thread
                        new Thread(() -> handleClient(clientSocket)).start();
                    } catch (IOException e) {
                        if (!serverSocket.isClosed()) {
                            log("Error accepting client connection: " + e.getMessage());
                        }
                    }
                }
            });
            return true;
        } catch (IOException e) {
            log("Failed to start server: " + e.getMessage());
            return false;
        }
    }
    
    private void handleClient(Socket clientSocket) {
        try (ObjectInputStream ois = new ObjectInputStream(clientSocket.getInputStream());
             ObjectOutputStream oos = new ObjectOutputStream(clientSocket.getOutputStream())) {
            
            Object obj = ois.readObject();
            
            if (obj instanceof VideoMessage) {
                VideoMessage videoMessage = (VideoMessage) obj;
                log("Received video: " + videoMessage.getFilename());
                
                // Check if this file hash already exists
                if (fileHashes.contains(videoMessage.getHash())) {
                    log("Duplicate video detected: " + videoMessage.getFilename());
                    oos.writeObject("DUPLICATE: File already exists");
                    oos.flush();
                    return;
                }
                
                // Not a duplicate, add to queue
                boolean added = videoQueue.offer(videoMessage);
                if (added) {
                    // Add hash to the set of known files
                    fileHashes.add(videoMessage.getHash());
                    oos.writeObject("SUCCESS: File queued for processing");
                } else {
                    log("Queue full, dropping video: " + videoMessage.getFilename());
                    oos.writeObject("ERROR: Queue full, please try again later");
                }
                oos.flush();
            }
            
        } catch (IOException | ClassNotFoundException e) {
            log("Error handling client: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                log("Error closing client socket: " + e.getMessage());
            }
        }
    }
    
    private void processVideoQueue(int threadId) {
        log("Consumer thread " + threadId + " started");
        
        while (!Thread.currentThread().isInterrupted()) {
            try {
                VideoMessage videoMessage = videoQueue.take();
                log("Thread " + threadId + " processing video: " + videoMessage.getFilename());
                
                // Save video file
                String filename = videoMessage.getId() + "_" + videoMessage.getFilename();
                Path filePath = Paths.get(UPLOAD_DIR, filename);
                
                try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                    fos.write(videoMessage.getData());
                }
                
                log("Video saved: " + filePath);
                
                // Update UI
                SwingUtilities.invokeLater(() -> addVideoToUI(videoMessage, filePath));
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log("Consumer thread " + threadId + " interrupted");
            } catch (IOException e) {
                log("Error saving video: " + e.getMessage());
            }
        }
    }
    
    private void addVideoToUI(VideoMessage videoMessage, Path filePath) {
        JPanel videoPanel = new JPanel(new BorderLayout());
        videoPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                new EmptyBorder(10, 10, 10, 10)));
        
        // Create thumbnail/info panel
        JLabel thumbnailLabel = new JLabel(videoMessage.getFilename());
        thumbnailLabel.setIcon(new ImageIcon(createDefaultThumbnail()));
        thumbnailLabel.setHorizontalTextPosition(JLabel.CENTER);
        thumbnailLabel.setVerticalTextPosition(JLabel.BOTTOM);
        
        // Add tooltip for preview info (actual preview would require video processing library)
        thumbnailLabel.setToolTipText("Hover to preview (first 10 seconds)");
        
        // Add click handler to "play" video
        thumbnailLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                playVideo(filePath);
            }
        });
        
        videoPanel.add(thumbnailLabel, BorderLayout.CENTER);
        
        // Add to list with some space
        videoListPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        videoListPanel.add(videoPanel);
        videoListPanel.revalidate();
        videoListPanel.repaint();
    }
    
    private Image createDefaultThumbnail() {
        // Create a simple placeholder thumbnail
        BufferedImage thumbnail = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = thumbnail.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, 160, 120);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Video Thumbnail", 30, 60);
        g2d.dispose();
        return thumbnail;
    }
    
    private void playVideo(Path filePath) {
        log("Playing video: " + filePath);
        
        // In a real application, you would use a media player library
        // For this example, we'll just try to open the file with the default system application
        try {
            Desktop.getDesktop().open(filePath.toFile());
        } catch (IOException e) {
            log("Error playing video: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                    "Could not play video. File saved at: " + filePath,
                    "Play Error", 
                    JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + System.currentTimeMillis() + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
    
    public void shutdown() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log("Error closing server socket: " + e.getMessage());
            }
        }
        serverThreadPool.shutdown();
        consumerThreadPool.shutdown();
    }
}

// ================ Main Application class ================
public class MediaUploadService {
    private static ProducerApp producerApp;
    private static ConsumerApp consumerApp;
    
    public static void main(String[] args) {
        // Create config.txt if it doesn't exist
        File configFile = new File("config.txt");
        if (!configFile.exists()) {
            try (PrintWriter writer = new PrintWriter(configFile)) {
                writer.println("p=3");
                writer.println("c=2");
                writer.println("q=5");
            } catch (IOException e) {
                System.err.println("Failed to create default config.txt: " + e.getMessage());
            }
        }
        
        SwingUtilities.invokeLater(() -> {
            try {
                // Load configuration
                Map<String, Integer> config = ConfigLoader.loadConfig("config.txt");
                int producerThreads = config.getOrDefault("p", 3);
                int consumerThreads = config.getOrDefault("c", 2);
                int queueSize = config.getOrDefault("q", 5);
                
                // Start both consumer and producer
                consumerApp = new ConsumerApp(consumerThreads, queueSize);
                
                // Position the consumer window
                consumerApp.setLocation(50, 50);
                
                // Short delay to ensure consumer starts first
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Start producer
                producerApp = new ProducerApp(producerThreads);
                
                // Position the producer window
                producerApp.setLocation(consumerApp.getX() + consumerApp.getWidth() + 20, 50);
                
                // Add shutdown hook to clean up resources
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (producerApp != null) producerApp.shutdown();
                    if (consumerApp != null) consumerApp.shutdown();
                }));
                
            } catch (IOException e) {
                System.err.println("Failed to load configuration: " + e.getMessage());
                
                // Use default values
                consumerApp = new ConsumerApp(2, 5);
                consumerApp.setLocation(50, 50);
                
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                
                producerApp = new ProducerApp(3);
                producerApp.setLocation(consumerApp.getX() + consumerApp.getWidth() + 20, 50);
            }
        });
    }
}