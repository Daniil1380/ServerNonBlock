import java.nio.channels.SocketChannel;
import java.time.ZoneId;

public class LoggedUser {
    private String name;
    private SocketChannel socket;
    private ZoneId zoneId;

    public String getName() {
        return name;
    }

    public SocketChannel getSocket() {
        return socket;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public LoggedUser(String name, SocketChannel socket, ZoneId zoneId) {
        this.name = name;
        this.socket = socket;
        this.zoneId = zoneId;
    }
}
