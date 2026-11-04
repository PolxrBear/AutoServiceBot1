package autoservice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// SQLite сохраняет данные в файл. Отдельный сервер базы не нужен.
public class BotData implements AutoCloseable {
    private final Connection connection;
    private static final String CREATE_REVIEWS = "CREATE TABLE IF NOT EXISTS reviews ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, client_id TEXT NOT NULL, "
            + "rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5), "
            + "review_text TEXT NOT NULL CHECK (length(review_text) BETWEEN 3 AND 1000))";

    public BotData() {
        this(defaultFile());
    }

    public BotData(Path file) {
        Connection opened = null;
        try {
            Path absolute = file.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            opened = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = opened.createStatement()) {
                statement.executeUpdate("PRAGMA busy_timeout = 5000");
                statement.executeUpdate("PRAGMA synchronous = FULL");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS balances ("
                        + "client_id TEXT PRIMARY KEY, bonus INTEGER NOT NULL CHECK (bonus >= 0))");
                statement.executeUpdate(CREATE_REVIEWS);
            }
            updateReviewsTable(opened);
            connection = opened;
        } catch (SQLException | IOException e) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException ignored) { }
            }
            throw new IllegalStateException("Не удалось открыть локальную бд", e);
        }
    }

    // Обновляем старую таблицу: лимит 1000 и несколько отзывов от одного клиента.
    private void updateReviewsTable(Connection database) throws SQLException {
        String tableSql;
        try (Statement query = database.createStatement();
             ResultSet result = query.executeQuery("SELECT sql FROM sqlite_master WHERE name = 'reviews'")) {
            result.next();
            tableSql = result.getString(1).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        }
        if (!tableSql.contains("between3and300") && !tableSql.contains("unique")) {
            return;
        }
        database.setAutoCommit(false);
        try (Statement query = database.createStatement()) {
            query.executeUpdate("ALTER TABLE reviews RENAME TO reviews_old");
            query.executeUpdate(CREATE_REVIEWS);
            query.executeUpdate("INSERT INTO reviews (id, client_id, rating, review_text) "
                    + "SELECT id, client_id, rating, review_text FROM reviews_old");
            query.executeUpdate("DROP TABLE reviews_old");
            database.commit();
        } catch (SQLException e) {
            database.rollback();
            throw e;
        } finally {
            database.setAutoCommit(true);
        }
    }

    private static Path defaultFile() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            localAppData = Path.of(System.getProperty("user.home"), ".local", "share").toString();
        }
        return Path.of(localAppData, "AutoServiceBot", "bot.db");
    }

    public Map<String, Integer> getServices() {
        Map<String, Integer> services = new LinkedHashMap<>();
        services.put("Комплексная мойка", 1200);
        services.put("Замена масла", 1800);
        services.put("Диагностика автомобиля", 2500);
        return services;
    }

    public synchronized int getBalance(String clientId) {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT bonus FROM balances WHERE client_id = ?")) {
            query.setString(1, clientId);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getInt("bonus") : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать бонусный баланс", e);
        }
    }

    public synchronized void setBalance(String clientId, int balance) {
        if (balance < 0) {
            throw new IllegalArgumentException("Баланс не может быть <0");
        }
        try (PreparedStatement query = connection.prepareStatement(
                "INSERT OR REPLACE INTO balances (client_id, bonus) VALUES (?, ?)")) {
            query.setString(1, clientId);
            query.setInt(2, balance);
            query.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось сохранить бонусный баланс", e);
        }
    }

    public synchronized void saveReview(String clientId, int rating, String text) {
        try (PreparedStatement query = connection.prepareStatement(
                "INSERT INTO reviews (client_id, rating, review_text) VALUES (?, ?, ?)")) {
            query.setString(1, clientId);
            query.setInt(2, rating);
            query.setString(3, text);
            query.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось сохранить отзыв", e);
        }
    }

    public synchronized List<String> getReviews() {
        List<String> reviews = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT rating, review_text FROM reviews ORDER BY id");
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                reviews.add(result.getInt("rating") + "/5 — " + result.getString("review_text"));
            }
            return reviews;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать отзывы", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось закрыть бд", e);
        }
    }
}
