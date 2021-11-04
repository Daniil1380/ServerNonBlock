import com.google.gson.Gson;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

public class Server implements Runnable {
    public final static String HOSTNAME = "127.0.0.1";
    public final static int PORT = 8511;
    public final static long TIMEOUT = 100;
    public static List<LoggedUser> loggedUsers = new ArrayList<>();
    public LoggedUser current;

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private Map<SocketChannel, byte[]> dataTracking = new HashMap<>();

    public Server() {
        init();
    }

    private void init() {
        if (selector != null) return;
        if (serverChannel != null) return;
        try {
            serverChannel = ServerSocketChannel.open();

            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(HOSTNAME, PORT));
            selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                selector.select(TIMEOUT);
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    for (LoggedUser loggedUser : loggedUsers) {
                        if (loggedUser.getSocket().equals(key.channel())){
                            current = loggedUser;
                        }
                    }
                    keys.remove();
                    try {
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isAcceptable()) {
                            accept(key);
                        }
                        if (key.isReadable()) {
                            read(key);
                        }
                        if (key.isWritable()) {
                            write(key);
                        }
                    }
                    catch (CancelledKeyException ignored){

                    }

                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            loggedUsers.remove(current);
            current = null;

        } finally {
            closeConnection();
        }
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        byte[] data = dataTracking.get(channel);
        dataTracking.remove(channel);
        key.interestOps(SelectionKey.OP_READ);
        for (LoggedUser loggedUser : loggedUsers) {
            try {
                loggedUser.getSocket().write(ByteBuffer.wrap(data));
            }
            catch (ClosedChannelException ignore){

            }
        }
    }

    // Nothing special, just closing our selector and socket.
    private void closeConnection() {
        if (selector != null) {
            try {
                selector.close();
                serverChannel.socket().close();
                serverChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverSocketChannel.accept();
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, SelectionKey.OP_READ);
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer readBuffer = ByteBuffer.allocate(100000);
        Gson gson = new Gson();
        readBuffer.clear();

        int read;
        try {
            read = channel.read(readBuffer);
        } catch (IOException e) {
            key.cancel();
            channel.close();
            return;
        }

        if (read == -1) {
            channel.close();
            key.cancel();
            return;
        }
        readBuffer.flip();
        byte[] data = new byte[100000];

        readBuffer.get(data, 0, read);
        for (LoggedUser loggedUser : loggedUsers) {
            if (loggedUser.getSocket().equals(key.channel())) {

                byte[] oldData = new byte[12];
                System.arraycopy(data, 0, oldData, 0, 12);
                int size = readHeader(oldData);
                int sizeFile = readSizeFileInHeader(oldData);

                byte[] fileInBytes = new byte[sizeFile];
                System.arraycopy(data, 12, fileInBytes, 0, sizeFile);

                byte[] newData = new byte[size];
                System.arraycopy(data, 12 + sizeFile, newData, 0, size);
                Message message = gson.fromJson(new String(newData), Message.class);

                if (sizeFile > 0){
                    Path destinationFile = Paths.get("files/"+message.getMessage().split("Файл передан:")[1].trim());
                    Files.write(destinationFile, fileInBytes);
                }
                if (message.getMessage().equals("/qqq")){
                    message.setMessage("Вышел с сервера");
                }
                echo(key, message.getMessage().getBytes(), loggedUser, fileInBytes);
                return;
            }
        }
        register(key, data);
    }

    public static int readHeader(byte[] data) throws IOException {
        byte[] size = new byte[2];
        System.arraycopy(data, 2, size, 0, 2);
        return byteArraySmallToInt(size);
    }

    public static int readSizeFileInHeader(byte[] data) throws IOException {
        byte[] size = new byte[4];
        System.arraycopy(data, 8, size, 0, 4);
        return byteArrayBigToInt(size);
    }

    public void register(SelectionKey key, byte[] data) throws IOException {
        int timezoneSize = readHeader(data);
        byte[] dataSecond = new byte[100000];
        System.arraycopy(data, 12 + timezoneSize, dataSecond, 0, 100000 - 12 - timezoneSize);
        byte[] timezone = new byte[timezoneSize];
        System.arraycopy(data, 12, timezone, 0, timezoneSize);
        Message message = new Gson().fromJson(new String(timezone), Message.class);
        ZoneId zoneId = ZoneId.of(message.getMessage());

        int nameSize = readHeader(dataSecond);
        byte[] nameByte = new byte[nameSize];
        System.arraycopy(data, 24 + timezoneSize, nameByte, 0, nameSize);
        Message messageName = new Gson().fromJson(new String(nameByte), Message.class);
        LoggedUser loggedUser = new LoggedUser(messageName.getMessage(), (SocketChannel) key.channel(), zoneId);
        loggedUsers.add(loggedUser);
        echo(key, ("Добро пожаловать, " + loggedUser.getName()).getBytes(), loggedUser, new byte[0]);
    }

    public static final int byteArraySmallToInt(byte[] bytes) {
        return (bytes[0] & 0xFF) << 8 | (bytes[1] & 0xFF);
    }

    public static final int byteArrayBigToInt(byte[] bytes) {
        return bytes[0] << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }

    private void echo(SelectionKey key, byte[] data, LoggedUser current, byte[] file) throws UnsupportedEncodingException {
        Gson gson = new Gson();
        for (LoggedUser loggedUser : loggedUsers) {
            Message message = new Message(current.getName(), LocalTime.now(loggedUser.getZoneId()).toString(), new String(data), null);
            String stringMessage = gson.toJson(message);
            SocketChannel socketChannel = loggedUser.getSocket();
            byte[] messageWithFile = concat(file, stringMessage.getBytes());
            byte[] messageWithFileWithHeader = concat(createHeader(stringMessage, false, false, false, file.length > 0, file.length), messageWithFile);
            dataTracking.put(socketChannel, messageWithFileWithHeader);
        }
        key.interestOps(SelectionKey.OP_WRITE);
    }

    public byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    public byte[] createHeader(String message, boolean startTimeCode, boolean startName, boolean finish, boolean file, int size) throws UnsupportedEncodingException {
        byte[] array = new byte[12];
        array[0] = (byte) (1);
        array[1] = (byte) (1);
        int messageSize = message.getBytes().length;
        array[2] = (byte) (messageSize / (int) Math.pow(2, 8));
        array[3] = (byte) (messageSize % (int) Math.pow(2, 8));
        array[4] = (byte) ((booleanToInt(startTimeCode) << 7) + (booleanToInt(startName) << 6)
                + (booleanToInt(finish) << 5) + (booleanToInt(file) << 4));
        byte[] hashcode = intToByteArray(message.hashCode());
        for (int i = 5; i < 8; i++) {
            array[i] = hashcode[i - 5];
        }
        byte[] sizeBytes = intToByteArrayBig(size);
        for (int i = 8; i < 12; i++) {
            array[i] = sizeBytes[i - 8];
        }
        return array;
    }

    public final byte[] intToByteArrayBig(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }

    public final byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }

    public int booleanToInt(boolean b) {
        return b ? 1 : 0;
    }

    public static void main(String[] args) {
        Thread server = new Thread(new Server());
        server.start();
    }
}
