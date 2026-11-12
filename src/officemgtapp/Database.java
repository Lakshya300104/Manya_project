package officemgtapp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

final class Database {
    private static final String NAME = "office_mgt";
    static final String ADMIN_USERNAME = "admin123";
    static final String ADMIN_PASSWORD = "admin_password";
    private static final String ADMIN_PHONE = "9999999999";

    private final String serverUrl;
    private final String options;
    private final Properties credentials;

    Database() throws IOException {
        Properties settings = new Properties();
        Path config = configPath();
        if (!Files.isRegularFile(config)) {
            throw new IOException("Create mysql.local.properties at " + config.toAbsolutePath().normalize()
                    + " with your MySQL connection settings.");
        }
        try (var reader = Files.newBufferedReader(config)) {
            settings.load(reader);
        }
        String url = settings.getProperty("url", "").strip();
        String user = settings.getProperty("user", "").strip();
        String password = settings.getProperty("password");
        if (!url.startsWith("jdbc:mysql://") || user.isEmpty() || password == null) {
            throw new IOException("Set the MySQL URL, user, and password in mysql.local.properties.");
        }
        URI endpoint;
        try {
            endpoint = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Use a JDBC URL such as jdbc:mysql://localhost:3306/.");
        }
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null) {
            throw new IOException("Enter the MySQL host and port in the URL and use the separate user and password fields.");
        }
        serverUrl = "jdbc:mysql://" + endpoint.getRawAuthority() + "/";
        options = endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery();
        credentials = new Properties();
        credentials.setProperty("user", user);
        credentials.setProperty("password", password);
        credentials.setProperty("connectTimeout", "5000");
        credentials.setProperty("socketTimeout", "15000");
    }

    private static Path configPath() {
        String packagedLocation = System.getProperty("office.mgt.config");
        if (packagedLocation == null) {
            return Path.of("mysql.local.properties");
        }
        Path config = Path.of(packagedLocation).toAbsolutePath().normalize();
        if (Files.isRegularFile(config)) {
            return config;
        }
        for (Path folder = config.getParent(); folder != null; folder = folder.getParent()) {
            Path projectConfig = folder.resolve("mysql.local.properties");
            if (Files.isRegularFile(folder.resolve("nbproject/project.properties"))
                    && Files.isRegularFile(projectConfig)) {
                return projectConfig;
            }
        }
        return config;
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(serverUrl + NAME + options, credentials);
    }

    void initialize() throws SQLException {
        try (Connection connection = DriverManager.getConnection(serverUrl + options, credentials);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS " + NAME + " CHARACTER SET utf8mb4");
        }
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet lock = statement.executeQuery("SELECT GET_LOCK('office_mgt_migrations', 10)")) {
                if (!lock.next() || lock.getInt(1) != 1) {
                    throw new SQLException("Could not acquire the database setup lock.");
                }
            }
            try {
                migrateAccounts(connection, statement);
                migrateOperations(connection, statement);
                migrateInventoryOrders(connection, statement);
            } finally {
                statement.execute("DO RELEASE_LOCK('office_mgt_migrations')");
            }
        }
    }

    private void migrateInventoryOrders(Connection connection, Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT 1 FROM schema_version WHERE version = 3")) {
            if (result.next()) {
                return;
            }
        }
        boolean linked;
        try (ResultSet columns = connection.getMetaData().getColumns(NAME, null, "order_items", "inventory_item_id")) {
            linked = columns.next();
        }
        if (!linked) {
            statement.executeUpdate("""
                    ALTER TABLE order_items ADD COLUMN inventory_item_id BIGINT,
                    ADD CONSTRAINT fk_order_items_inventory FOREIGN KEY (inventory_item_id) REFERENCES inventory_items(id)
                    """);
        }
        statement.executeUpdate("INSERT INTO schema_version VALUES (3, UTC_TIMESTAMP(6))");
    }

    private void migrateAccounts(Connection connection, Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INT PRIMARY KEY,
                    applied_at DATETIME(6) NOT NULL
                ) ENGINE=InnoDB
                """);
        try (ResultSet result = statement.executeQuery("SELECT version FROM schema_version WHERE version = 1")) {
            if (result.next()) {
                return;
            }
        }
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(50) NOT NULL UNIQUE,
                    phone VARCHAR(16) UNIQUE,
                    password_hash VARCHAR(255) NOT NULL,
                    role ENUM('ROOT_ADMIN', 'ADMIN', 'EMPLOYEE') NOT NULL,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME(6) NOT NULL
                ) ENGINE=InnoDB
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS admin_requests (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(50) NOT NULL,
                    phone VARCHAR(16) NOT NULL,
                    password_hash VARCHAR(255),
                    status ENUM('PENDING', 'APPROVED', 'REJECTED') NOT NULL DEFAULT 'PENDING',
                    pending_username VARCHAR(50) GENERATED ALWAYS AS
                        (CASE WHEN status = 'PENDING' THEN username END) STORED UNIQUE,
                    pending_phone VARCHAR(16) GENERATED ALWAYS AS
                        (CASE WHEN status = 'PENDING' THEN phone END) STORED UNIQUE,
                    requested_at DATETIME(6) NOT NULL,
                    reviewed_by BIGINT,
                    reviewed_at DATETIME(6),
                    created_user_id BIGINT,
                    FOREIGN KEY (reviewed_by) REFERENCES users(id),
                    FOREIGN KEY (created_user_id) REFERENCES users(id)
                ) ENGINE=InnoDB
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS login_sessions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    logged_in_at DATETIME(6) NOT NULL,
                    logged_out_at DATETIME(6),
                    INDEX (user_id, logged_in_at),
                    FOREIGN KEY (user_id) REFERENCES users(id)
                ) ENGINE=InnoDB
                """);
        connection.setAutoCommit(false);
        try (PreparedStatement admin = connection.prepareStatement("""
                INSERT INTO users (username, phone, password_hash, role, created_at)
                VALUES (?, ?, ?, 'ROOT_ADMIN', UTC_TIMESTAMP(6))
                """)) {
            admin.setString(1, ADMIN_USERNAME);
            admin.setString(2, ADMIN_PHONE);
            admin.setString(3, Passwords.hash(ADMIN_PASSWORD.toCharArray()));
            admin.executeUpdate();
            statement.executeUpdate("INSERT INTO schema_version VALUES (1, UTC_TIMESTAMP(6))");
            connection.commit();
        } catch (SQLException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void migrateOperations(Connection connection, Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT 1 FROM schema_version WHERE version = 2")) {
            if (result.next()) {
                return;
            }
        }
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS customers (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(120) NOT NULL,
                    email VARCHAR(254),
                    city VARCHAR(100),
                    gst VARCHAR(30),
                    pan VARCHAR(30),
                    created_by BIGINT NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    archived_at DATETIME(6),
                    FOREIGN KEY (created_by) REFERENCES users(id)
                ) ENGINE=InnoDB
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS inventory_items (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    product VARCHAR(120) NOT NULL,
                    manufacturer VARCHAR(120),
                    grade VARCHAR(60),
                    thickness VARCHAR(60),
                    stock_quantity INT NOT NULL CHECK (stock_quantity >= 0),
                    created_by BIGINT NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    FOREIGN KEY (created_by) REFERENCES users(id)
                ) ENGINE=InnoDB
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS orders (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    current_status VARCHAR(20) NOT NULL,
                    expected_delivery_date DATE,
                    created_by BIGINT NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    demo_key VARCHAR(60) UNIQUE,
                    FOREIGN KEY (customer_id) REFERENCES customers(id),
                    FOREIGN KEY (created_by) REFERENCES users(id)
                ) ENGINE=InnoDB
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS order_items (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_id BIGINT NOT NULL,
                    product VARCHAR(120) NOT NULL,
                    quantity INT NOT NULL CHECK (quantity > 0),
                    unit_price DECIMAL(14,2) NOT NULL CHECK (unit_price > 0),
                    FOREIGN KEY (order_id) REFERENCES orders(id)
                ) ENGINE=InnoDB
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS order_status_history (
                    order_id BIGINT NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    changed_by BIGINT NOT NULL,
                    changed_at DATETIME(6) NOT NULL,
                    PRIMARY KEY (order_id, status),
                    FOREIGN KEY (order_id) REFERENCES orders(id),
                    FOREIGN KEY (changed_by) REFERENCES users(id)
                ) ENGINE=InnoDB
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS payments (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_id BIGINT NOT NULL,
                    amount DECIMAL(14,2) NOT NULL CHECK (amount > 0),
                    method VARCHAR(30) NOT NULL,
                    payment_reference VARCHAR(120),
                    recorded_by BIGINT NOT NULL,
                    recorded_at DATETIME(6) NOT NULL,
                    FOREIGN KEY (order_id) REFERENCES orders(id),
                    FOREIGN KEY (recorded_by) REFERENCES users(id)
                ) ENGINE=InnoDB
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS payment_reminders (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_id BIGINT NOT NULL,
                    balance DECIMAL(14,2) NOT NULL CHECK (balance > 0),
                    recorded_by BIGINT NOT NULL,
                    recorded_at DATETIME(6) NOT NULL,
                    FOREIGN KEY (order_id) REFERENCES orders(id),
                    FOREIGN KEY (recorded_by) REFERENCES users(id)
                ) ENGINE=InnoDB
                """);
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE users SET username = ?, phone = ?, password_hash = ?
                WHERE role = 'ROOT_ADMIN' AND username = 'office_admin'
                """)) {
            update.setString(1, ADMIN_USERNAME);
            update.setString(2, ADMIN_PHONE);
            update.setString(3, Passwords.hash(ADMIN_PASSWORD.toCharArray()));
            update.executeUpdate();
            statement.executeUpdate("INSERT INTO schema_version VALUES (2, UTC_TIMESTAMP(6))");
            connection.commit();
        } catch (SQLException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
