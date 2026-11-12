import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class CheckMySql {
    public static void main(String[] args) {
        boolean seedTest = args.length == 1 && args[0].equals("--seed-test");
        if (args.length != 0 && !seedTest) {
            System.err.println("Usage: CheckMySql.java [--seed-test]");
            System.exit(1);
            return;
        }

        Path configFile = Path.of("mysql.local.properties");
        Properties settings = new Properties();

        try (Reader reader = Files.newBufferedReader(configFile)) {
            settings.load(reader);
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("Cannot read mysql.local.properties. Run the checker from the project folder and verify the file.");
            System.exit(1);
            return;
        }

        String url = settings.getProperty("url", "").strip();
        String user = settings.getProperty("user", "").strip();
        if (!url.startsWith("jdbc:mysql://") || user.isEmpty() || !settings.containsKey("password")) {
            System.err.println("Set url, user, and password in mysql.local.properties. Leave password empty only if your MySQL account uses an empty password.");
            System.exit(1);
            return;
        }

        Properties connectionOptions = new Properties();
        connectionOptions.setProperty("user", user);
        connectionOptions.setProperty("password", settings.getProperty("password"));
        connectionOptions.setProperty("connectTimeout", "5000");
        connectionOptions.setProperty("socketTimeout", "5000");

        try (Connection connection = DriverManager.getConnection(url, connectionOptions);
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
                result.next();
                String database = result.getString(1);
                if (database == null) {
                    System.out.println("Connected to MySQL successfully.");
                } else {
                    System.out.println("Connected to MySQL database: " + database);
                }
            }
            if (seedTest) {
                createAndVerifyTestRow(statement);
            }
        } catch (SQLException ex) {
            System.err.printf("MySQL check failed (SQLState %s, error %d).%n", ex.getSQLState(), ex.getErrorCode());
            System.exit(1);
        }
    }

    private static void createAndVerifyTestRow(Statement statement) throws SQLException {
        statement.executeUpdate("CREATE DATABASE IF NOT EXISTS officemgt_connection_test");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS officemgt_connection_test.jdbc_test (
                    id INT PRIMARY KEY,
                    message VARCHAR(50) NOT NULL
                )
                """);
        statement.executeUpdate("""
                INSERT INTO officemgt_connection_test.jdbc_test (id, message)
                VALUES (1, 'JDBC test row')
                ON DUPLICATE KEY UPDATE id = id
                """);
        try (ResultSet result = statement.executeQuery("""
                SELECT message FROM officemgt_connection_test.jdbc_test WHERE id = 1
                """)) {
            if (!result.next() || !"JDBC test row".equals(result.getString("message"))) {
                throw new SQLException("The dummy row did not match the expected value.", "HY000");
            }
        }
        System.out.println("Verified dummy row in officemgt_connection_test.jdbc_test.");
    }
}
