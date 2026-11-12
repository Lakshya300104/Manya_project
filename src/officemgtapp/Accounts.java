package officemgtapp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Accounts {
    enum Role { ROOT_ADMIN, ADMIN, EMPLOYEE }

    record Session(long id, long userId, String username, Role role) {}
    record Activity(String username, String login, String logout, int dailyLogins) {}

    static final class ResetChallenge {
        private final long userId;
        private final Instant expiresAt = Instant.now().plusSeconds(300);
        private boolean verified;
        private boolean used;

        private ResetChallenge(long userId) {
            this.userId = userId;
        }
    }
    record Request(long id, String username, String phone, String requestedAt) {
        @Override
        public String toString() {
            return username + " · " + phone + " · " + requestedAt + " UTC";
        }
    }

    private final Database database;

    Accounts(Database database) {
        this.database = database;
    }

    static boolean demoResetEnabled() {
        String setting = System.getenv("OFFICE_MGT_DEMO_RESET");
        return setting == null || "true".equalsIgnoreCase(setting);
    }

    ResetChallenge beginReset(String phone) throws SQLException {
        if (!demoResetEnabled()) {
            throw new IllegalArgumentException("Demo password reset is disabled.");
        }
        String number;
        try {
            number = normalizePhone(phone);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        try (Connection connection = database.connect();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id FROM users WHERE phone = ? AND active
                     """)) {
            query.setString(1, number);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new ResetChallenge(result.getLong("id")) : null;
            }
        }
    }

    boolean verifyResetCode(ResetChallenge challenge, char[] pin) {
        if (!validReset(challenge) || challenge.verified) {
            return false;
        }
        challenge.verified = pin.length == 4 && pin[0] == '0' && pin[1] == '0' && pin[2] == '0' && pin[3] == '0';
        challenge.used = !challenge.verified;
        return challenge.verified;
    }

    private boolean validReset(ResetChallenge challenge) {
        return demoResetEnabled() && !challenge.used && Instant.now().isBefore(challenge.expiresAt);
    }

    void resetPassword(ResetChallenge challenge, char[] password) throws SQLException {
        if (!validReset(challenge) || !challenge.verified) {
            throw new IllegalArgumentException("This reset request is invalid or has expired. Start again.");
        }
        validatePassword(password);
        try (Connection connection = database.connect();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE users SET password_hash = ? WHERE id = ? AND active
                     """)) {
            update.setString(1, Passwords.hash(password));
            update.setLong(2, challenge.userId);
            if (update.executeUpdate() != 1) {
                throw new IllegalArgumentException("This account is unavailable for reset.");
            }
            challenge.used = true;
        }
    }

    void signup(String username, String phone, char[] password, boolean admin) throws SQLException {
        username = username.strip().toLowerCase(Locale.ROOT);
        if (!username.matches("[a-z0-9_.-]{3,50}")) {
            throw new IllegalArgumentException("Use 3–50 letters, digits, dots, underscores, or hyphens for the username.");
        }
        phone = normalizePhone(phone);
        validatePassword(password);
        String hash = Passwords.hash(password);
        try (Connection connection = database.connect()) {
            lockSignups(connection);
            try {
                checkAvailable(connection, username, phone);
                if (admin) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO admin_requests (username, phone, password_hash, requested_at)
                            VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                            """)) {
                        insert.setString(1, username);
                        insert.setString(2, phone);
                        insert.setString(3, hash);
                        insert.executeUpdate();
                    }
                } else {
                    createUser(connection, username, phone, hash, Role.EMPLOYEE);
                }
            } finally {
                unlockSignups(connection);
            }
        }
    }

    Session login(String username, char[] password) throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement lookup = connection.prepareStatement("""
                     SELECT id, username, password_hash, role FROM users WHERE username = ? AND active
                     """)) {
            lookup.setString(1, username.strip().toLowerCase(Locale.ROOT));
            try (ResultSet result = lookup.executeQuery()) {
                if (!result.next() || !Passwords.matches(password, result.getString("password_hash"))) {
                    return null;
                }
                long userId = result.getLong("id");
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO login_sessions (user_id, logged_in_at) VALUES (?, UTC_TIMESTAMP(6))
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    insert.setLong(1, userId);
                    insert.executeUpdate();
                    return new Session(generatedId(insert), userId, result.getString("username"),
                            Role.valueOf(result.getString("role")));
                }
            }
        }
    }

    void logout(Session session) throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE login_sessions SET logged_out_at = UTC_TIMESTAMP(6)
                     WHERE id = ? AND user_id = ? AND logged_out_at IS NULL
                     """)) {
            update.setLong(1, session.id());
            update.setLong(2, session.userId());
            update.executeUpdate();
        }
    }

    List<Activity> employeeActivity(Session reviewer, LocalDate day) throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT employee.username, activity.logged_in_at, activity.logged_out_at,
                            COUNT(*) OVER (PARTITION BY employee.id) AS daily_logins
                     FROM login_sessions activity JOIN users employee ON employee.id = activity.user_id
                     WHERE employee.role = 'EMPLOYEE' AND activity.logged_in_at >= ? AND activity.logged_in_at < ?
                       AND EXISTS (
                           SELECT 1 FROM login_sessions viewer_session JOIN users viewer
                               ON viewer.id = viewer_session.user_id
                           WHERE viewer_session.id = ? AND viewer.id = ? AND viewer_session.logged_out_at IS NULL
                             AND viewer.active AND viewer.role IN ('ROOT_ADMIN', 'ADMIN')
                       )
                     ORDER BY employee.username, activity.logged_in_at
                     """)) {
            query.setObject(1, day.atStartOfDay());
            query.setObject(2, day.plusDays(1).atStartOfDay());
            query.setLong(3, reviewer.id());
            query.setLong(4, reviewer.userId());
            try (ResultSet result = query.executeQuery()) {
                List<Activity> activity = new ArrayList<>();
                while (result.next()) {
                    activity.add(new Activity(result.getString("username"), result.getString("logged_in_at"),
                            result.getString("logged_out_at"), result.getInt("daily_logins")));
                }
                return activity;
            }
        }
    }

    List<Request> pendingRequests(Session reviewer) throws SQLException {
        try (Connection connection = database.connect()) {
            requireRootAdmin(connection, reviewer);
            try (Statement query = connection.createStatement();
                 ResultSet result = query.executeQuery("""
                         SELECT id, username, phone, requested_at FROM admin_requests
                         WHERE status = 'PENDING' ORDER BY requested_at, id
                         """)) {
                List<Request> requests = new ArrayList<>();
                while (result.next()) {
                    requests.add(new Request(result.getLong("id"), result.getString("username"),
                            result.getString("phone"), result.getString("requested_at")));
                }
                return requests;
            }
        }
    }

    void review(Session reviewer, long requestId, boolean approved) throws SQLException {
        try (Connection connection = database.connect()) {
            lockSignups(connection);
            try {
                connection.setAutoCommit(false);
                requireRootAdmin(connection, reviewer);
                Long userId = null;
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT username, phone, password_hash FROM admin_requests
                        WHERE id = ? AND status = 'PENDING' FOR UPDATE
                        """)) {
                    select.setLong(1, requestId);
                    try (ResultSet request = select.executeQuery()) {
                        if (!request.next()) {
                            throw new IllegalArgumentException("This request has already been reviewed.");
                        }
                        if (approved) {
                            userId = createUser(connection, request.getString("username"),
                                    request.getString("phone"), request.getString("password_hash"), Role.ADMIN);
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE admin_requests SET status = ?, reviewed_by = ?, reviewed_at = UTC_TIMESTAMP(6),
                            created_user_id = ?, password_hash = NULL WHERE id = ?
                        """)) {
                    update.setString(1, approved ? "APPROVED" : "REJECTED");
                    update.setLong(2, reviewer.userId());
                    update.setObject(3, userId);
                    update.setLong(4, requestId);
                    update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                unlockSignups(connection);
            }
        }
    }

    private static void requireRootAdmin(Connection connection, Session session) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM login_sessions s JOIN users u ON u.id = s.user_id
                WHERE s.id = ? AND u.id = ? AND s.logged_out_at IS NULL AND u.active AND u.role = 'ROOT_ADMIN'
                """)) {
            query.setLong(1, session.id());
            query.setLong(2, session.userId());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Only the original admin can review these requests.");
                }
            }
        }
    }

    private static void checkAvailable(Connection connection, String username, String phone) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM users WHERE username = ? OR phone = ?
                UNION ALL
                SELECT 1 FROM admin_requests WHERE pending_username = ? OR pending_phone = ?
                LIMIT 1
                """)) {
            query.setString(1, username);
            query.setString(2, phone);
            query.setString(3, username);
            query.setString(4, phone);
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    throw new IllegalArgumentException("That username or phone number is already registered or pending approval.");
                }
            }
        }
    }

    private static long createUser(Connection connection, String username, String phone, String hash, Role role)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO users (username, phone, password_hash, role, created_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, username);
            insert.setString(2, phone);
            insert.setString(3, hash);
            insert.setString(4, role.name());
            insert.executeUpdate();
            return generatedId(insert);
        }
    }

    private static long generatedId(PreparedStatement insert) throws SQLException {
        try (ResultSet keys = insert.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("MySQL did not return the generated ID.");
            }
            return keys.getLong(1);
        }
    }

    private static String normalizePhone(String phone) {
        String normalized = phone.replaceAll("[ ().-]", "");
        if (!normalized.matches("\\+?[0-9]{7,15}")) {
            throw new IllegalArgumentException("Enter a phone number with 7–15 digits and an optional country code.");
        }
        return normalized;
    }

    private static void validatePassword(char[] password) {
        if (password.length < 12 || password.length > 128) {
            throw new IllegalArgumentException("Use a password between 12 and 128 characters.");
        }
    }

    private static void lockSignups(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT GET_LOCK('office_mgt_signups', 10)")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new SQLException("Could not acquire the account signup lock.");
            }
        }
    }

    private static void unlockSignups(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DO RELEASE_LOCK('office_mgt_signups')");
        }
    }
}
