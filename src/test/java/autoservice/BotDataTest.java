package autoservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

class BotDataTest {
    @TempDir Path directory;

    @Test
    void balanceAndReviewsSurviveReopeningDatabase() {
        Path file = directory.resolve("bot");
        try (BotData data = new BotData(file)) {
            data.setBalance("tg:7", 250);
            data.saveReview("tg:7", 5, "Хорошая мойка");
            data.saveReview("console:local", 4, "Всё хорошо");
        }
        assertTrue(Files.exists(file));
        try (BotData data = new BotData(file)) {
            assertEquals(250, data.getBalance("tg:7"));
            assertEquals(0, data.getBalance("console:local"));
            assertEquals(List.of("5/5 — Хорошая мойка", "4/5 — Всё хорошо"), data.getReviews());
            data.setBalance("tg:7", 300);
            data.saveReview("tg:7", 3, "Изменённый отзыв");
        }
        try (BotData data = new BotData(file)) {
            assertEquals(300, data.getBalance("tg:7"));
            assertEquals(List.of("5/5 — Хорошая мойка", "4/5 — Всё хорошо", "3/5 — Изменённый отзыв"), data.getReviews());
        }
    }

    @Test
    void invalidBalanceDoesNotReplacePreviousValue() {
        try (BotData data = new BotData(directory.resolve("bot"))) {
            data.setBalance("a", 120);
            assertThrows(IllegalArgumentException.class, () -> data.setBalance("a", -1));
            assertEquals(120, data.getBalance("a"));
        }
    }

    @Test
    void invalidReviewDoesNotReplacePreviousValue() {
        try (BotData data = new BotData(directory.resolve("bot"))) {
            data.saveReview("a", 5, "Хорошая мойка");
            assertThrows(IllegalStateException.class, () -> data.saveReview("a", 6, "Ошибка оценки"));
            assertEquals(List.of("5/5 — Хорошая мойка"), data.getReviews());
        }
    }

    @Test
    void userTextIsStoredAsData() {
        try (BotData data = new BotData(directory.resolve("bot"))) {
            String client = "a' OR '1'='1";
            String text = "Мойка 'Отлично'; текст с кавычками";
            data.setBalance(client, 50);
            data.saveReview(client, 5, text);
            assertEquals(50, data.getBalance(client));
            assertEquals(0, data.getBalance("a"));
            assertEquals(List.of("5/5 — " + text), data.getReviews());
        }
    }

    @Test
    void concurrentRequestsAreNotLost() throws Exception {
        Path file = directory.resolve("bot");
        try (BotData data = new BotData(file); ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int number = i;
                tasks.add(pool.submit(() -> {
                    data.setBalance("client" + number, number);
                    data.saveReview("client" + number, 5, "Отзыв " + number);
                }));
            }
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        }
        try (BotData data = new BotData(file)) {
            assertEquals(20, data.getReviews().size());
            for (int i = 0; i < 20; i++) {
                assertEquals(i, data.getBalance("client" + i));
            }
        }
    }

    @Test
    void databaseFailureIsShownWithoutInternalDetails() {
        BotData data = new BotData(directory.resolve("bot"));
        BotLogic logic = new BotLogic(data);
        data.close();
        BotReply error = logic.handleUserCommand("a", "/balance");
        assertFalse(error.getText().isBlank());
        assertFalse(error.getText().contains("SQLException"));
        assertFalse(error.getText().contains("SELECT"));
        assertEquals("/start", error.getButtons().get("Главное меню"));
        assertThrows(IllegalStateException.class, () -> data.setBalance("a", 10));
        assertThrows(IllegalStateException.class, data::getReviews);
    }

    @Test
    void databaseCannotBeCreatedInsideAFile() throws Exception {
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        assertThrows(IllegalStateException.class, () -> new BotData(blocked.resolve("bot")));
    }

    private void createOldDatabase(Path file) throws Exception {
        try (Connection database = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement query = database.createStatement()) {
            query.executeUpdate("CREATE TABLE balances (client_id TEXT PRIMARY KEY, bonus INTEGER NOT NULL)");
            query.executeUpdate("INSERT INTO balances VALUES ('a', 250)");
            query.executeUpdate("CREATE TABLE reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "client_id TEXT NOT NULL UNIQUE, rating INTEGER NOT NULL, "
                    + "review_text TEXT NOT NULL CHECK (length(review_text) BETWEEN 3 AND 300))");
            query.executeUpdate("INSERT INTO reviews (client_id, rating, review_text) VALUES ('a', 5, 'Старый отзыв')");
        }
    }

    @Test
    void oldDatabaseIsUpgradedWithoutLosingReviewsAndBonuses() throws Exception {
        Path file = directory.resolve("old.db");
        createOldDatabase(file);
        try (BotData data = new BotData(file)) {
            assertEquals(250, data.getBalance("a"));
            assertEquals(List.of("5/5 — Старый отзыв"), data.getReviews());
            data.saveReview("b", 4, "x".repeat(1000));
            assertThrows(IllegalStateException.class, () -> data.saveReview("b", 3, "x".repeat(1001)));
            assertEquals(2, data.getReviews().size());
        }
        try (BotData reopened = new BotData(file)) {
            assertEquals(250, reopened.getBalance("a"));
            assertEquals(List.of("5/5 — Старый отзыв", "4/5 — " + "x".repeat(1000)), reopened.getReviews());
        }
    }

    @Test
    void failedUpgradeLeavesOldDataIntact() throws Exception {
        Path file = directory.resolve("old.db");
        createOldDatabase(file);
        try (Connection database = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement query = database.createStatement()) {
            query.executeUpdate("CREATE TABLE reviews_old (keep_me TEXT)");
        }
        assertThrows(IllegalStateException.class, () -> new BotData(file));
        try (Connection database = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement query = database.createStatement();
             ResultSet result = query.executeQuery("SELECT review_text FROM reviews")) {
            assertTrue(result.next());
            assertEquals("Старый отзыв", result.getString(1));
        }
    }

    @Test
    void existingUniqueClientConstraintIsRemovedWithoutLosingData() throws Exception {
        Path file = directory.resolve("unique.db");
        try (Connection database = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement query = database.createStatement()) {
            query.executeUpdate("CREATE TABLE reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "client_id TEXT NOT NULL UNIQUE, rating INTEGER NOT NULL, "
                    + "review_text TEXT NOT NULL CHECK (length(review_text) BETWEEN 3 AND 1000))");
            query.executeUpdate("INSERT INTO reviews (client_id, rating, review_text) VALUES ('a', 5, 'Первый отзыв')");
        }
        try (BotData data = new BotData(file)) {
            data.saveReview("a", 4, "Второй отзыв");
            assertEquals(List.of("5/5 — Первый отзыв", "4/5 — Второй отзыв"), data.getReviews());
        }
        try (BotData data = new BotData(file)) {
            assertEquals(2, data.getReviews().size());
            data.saveReview("a", 3, "Третий отзыв");
            assertEquals(3, data.getReviews().size());
        }
    }
}
