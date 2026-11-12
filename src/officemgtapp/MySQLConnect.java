package officemgtapp;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

public final class MySQLConnect {
    private MySQLConnect() {}

    public static Connection ConnectDB() {
        try {
            return new Database().connect();
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("Could not connect to office_mgt. Check mysql.local.properties.", ex);
        }
    }
}
