package com.inpuzah.club.core.persistence;

import com.inpuzah.club.core.config.DatabaseConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DatabaseManagerTest {

    @Test
    void connectFailsFastWhenDatabaseIsUnreachable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        DatabaseConfig config = new DatabaseConfig("127.0.0.1", port, "club_dev", "club_app", "pw", "disable", 2, 1000);

        assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> assertThrows(RuntimeException.class, () -> DatabaseManager.connect(config)));
    }
}
