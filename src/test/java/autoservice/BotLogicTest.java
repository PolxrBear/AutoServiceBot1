package autoservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BotLogicTest {
    @TempDir Path directory;
    BotData data;
    BotLogic logic;

    @BeforeEach
    void setUp() {
        data = new BotData(directory.resolve("bot"));
        logic = new BotLogic(data);
    }

    @AfterEach
    void close() {
        data.close();
    }

    private BotReply leaveReview(String client, int rating, String text) {
        logic.handleUserCommand(client, "/review");
        logic.handleUserCommand(client, "/rating " + rating);
        return logic.handleUserCommand(client, text);
    }

    @Test
    void menuServicesAndBalances() {
        BotReply menu = logic.handleUserCommand("a", "/start");
        assertTrue(menu.isMainMenu());
        assertFalse(logic.handleUserCommand("a", "/services").isMainMenu());
        assertEquals(4, menu.getButtons().size());
        assertEquals(menu.getText(), logic.handleUserCommand("a", " /HELP ").getText());
        assertEquals("/services", menu.getButtons().get("Услуги и цены"));
        assertTrue(logic.handleUserCommand("a", "/services").getText().contains("Комплексная мойка — 1200 ₽"));
        data.setBalance("a", 250);
        assertTrue(logic.handleUserCommand("a", "/balance").getText().contains("250 бонусов"));
        assertTrue(logic.handleUserCommand("b", "/balance").getText().contains("0 бонусов"));
        assertThrows(UnsupportedOperationException.class, () -> menu.getButtons().clear());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void starsThenText(int rating) {
        BotReply stars = logic.handleUserCommand("a", "/review");
        assertEquals(6, stars.getButtons().size());
        for (int i = 1; i <= 5; i++) {
            assertEquals("/rating " + i, stars.getButtons().get("⭐".repeat(i)));
        }
        assertTrue(data.getReviews().isEmpty());
        BotReply prompt = logic.handleUserCommand("a", stars.getButtons().get("⭐".repeat(rating)));
        assertTrue(prompt.getText().contains("⭐".repeat(rating)));
        assertTrue(logic.isWaitingForReviewText("a"));
        assertTrue(data.getReviews().isEmpty());
        BotReply saved = logic.handleUserCommand("a", "Хорошая мойка");
        assertEquals(List.of(rating + "/5 — Хорошая мойка"), data.getReviews());
        assertFalse(logic.isWaitingForReviewText("a"));
        assertEquals("/start", saved.getButtons().get("Главное меню"));
    }

    @Test
    void textBeforeRatingAndOldCommandDoNotPublishReview() {
        logic.handleUserCommand("a", "/review 5 Старый способ");
        BotReply reply = logic.handleUserCommand("a", "Сначала мойка");
        assertEquals("/rating 5", reply.getButtons().get("⭐⭐⭐⭐⭐"));
        assertFalse(logic.isWaitingForReviewText("a"));
        assertTrue(data.getReviews().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "abc", "5 текст", ""})
    void invalidRatingIsNotAccepted(String rating) {
        logic.handleUserCommand("a", "/review");
        BotReply reply = logic.handleUserCommand("a", "/rating " + rating);
        assertTrue(reply.getButtons().containsValue("/rating 1"));
        assertFalse(logic.isWaitingForReviewText("a"));
        assertTrue(data.getReviews().isEmpty());
    }

    @Test
    void oldRatingButtonCannotStartOrRepeatAReview() {
        logic.handleUserCommand("a", "/rating 5");
        assertFalse(logic.isWaitingForReviewText("a"));
        leaveReview("a", 5, "Хорошая мойка");
        logic.handleUserCommand("a", "/rating 1");
        logic.handleUserCommand("a", "Случайный текст");
        assertEquals(List.of("5/5 — Хорошая мойка"), data.getReviews());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ab", "первая\nвторая"})
    void invalidTextCanBeCorrected(String text) {
        logic.handleUserCommand("a", "/review");
        logic.handleUserCommand("a", "/rating 4");
        BotReply error = logic.handleUserCommand("a", text);
        assertEquals("/cancel", error.getButtons().get("Отменить"));
        assertTrue(logic.isWaitingForReviewText("a"));
        assertTrue(data.getReviews().isEmpty());
        logic.handleUserCommand("a", "Исправленный текст");
        assertEquals(List.of("4/5 — Исправленный текст"), data.getReviews());
    }

    @Test
    void textLengthBoundaries() {
        leaveReview("short", 1, "abc");
        leaveReview("long", 5, "x".repeat(1000));
        assertEquals(2, data.getReviews().size());
        logic.handleUserCommand("a", "/review");
        logic.handleUserCommand("a", "/rating 3");
        BotReply error = logic.handleUserCommand("a", "x".repeat(1001));
        assertTrue(error.getText().contains("1000"));
        assertTrue(logic.isWaitingForReviewText("a"));
        assertEquals(2, data.getReviews().size());
    }

    @Test
    void draftsOfDifferentClientsDoNotMix() {
        logic.handleUserCommand("tg:7", "/review");
        logic.handleUserCommand("tg:7", "/rating 5");
        logic.handleUserCommand("console:local", "/review");
        logic.handleUserCommand("console:local", "/rating 2");
        logic.handleUserCommand("console:local", "Отзыв из консоли");
        logic.handleUserCommand("tg:7", "Отзыв из Telegram");
        assertEquals(List.of("2/5 — Отзыв из консоли", "5/5 — Отзыв из Telegram"), data.getReviews());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/cancel", "/start", "/help", "/services", "/balance", "/reviews"})
    void navigationCancelsDraft(String command) {
        logic.handleUserCommand("a", "/review");
        logic.handleUserCommand("a", "/rating 5");
        logic.handleUserCommand("a", command);
        assertFalse(logic.isWaitingForReviewText("a"));
        logic.handleUserCommand("a", "Не публиковать");
        assertTrue(data.getReviews().isEmpty());
    }

    @Test
    void ratingCanBeChangedBeforeSavingAndBothReviewsRemain() {
        leaveReview("a", 5, "Первый отзыв");
        logic.handleUserCommand("a", "/review");
        logic.handleUserCommand("a", "/rating 2");
        logic.handleUserCommand("a", "/review");
        assertFalse(logic.isWaitingForReviewText("a"));
        assertEquals(1, data.getReviews().size());
        logic.handleUserCommand("a", "/rating 4");
        logic.handleUserCommand("a", "Новая версия");
        assertEquals(List.of("5/5 — Первый отзыв", "4/5 — Новая версия"), data.getReviews());
    }

    @Test
    void lastFiveReviewsFitIntoTelegramPages() {
        for (int i = 0; i < 12; i++) {
            leaveReview("client" + i, 5, "Отзыв-" + i + "-" + "x".repeat(980));
        }
        StringBuilder all = new StringBuilder();
        BotReply page = logic.handleUserCommand("reader", "/reviews");
        int pageCount = 0;
        while (true) {
            assertTrue(page.getText().length() <= 4096);
            all.append(page.getText());
            pageCount++;
            assertTrue(pageCount <= 2);
            String next = page.getButtons().get("Далее");
            if (next == null) break;
            page = logic.handleUserCommand("reader", next);
            assertTrue(page.getButtons().containsKey("Назад"));
        }
        assertEquals(2, pageCount);
        for (int i = 7; i < 12; i++) {
            String marker = "Отзыв-" + i + "-";
            assertTrue(all.indexOf(marker) >= 0);
            assertEquals(all.indexOf(marker), all.lastIndexOf(marker));
        }
        assertFalse(all.toString().contains("Отзыв-0-"));
        assertFalse(all.toString().contains("Отзыв-1-"));
        assertFalse(all.toString().contains("Отзыв-6-"));
        BotReply previous = logic.handleUserCommand("reader", page.getButtons().get("Назад"));
        assertTrue(previous.getButtons().containsKey("Далее"));
    }

    @Test
    void fiveLatestShortReviewsFromOneClientAreShownTogether() {
        for (int i = 1; i <= 7; i++) {
            leaveReview("same-client", 5, "Отзыв-" + i + "-");
        }
        assertEquals(7, data.getReviews().size());
        BotReply reply = logic.handleUserCommand("reader", "/reviews");
        for (int i = 3; i <= 7; i++) {
            assertTrue(reply.getText().contains("Отзыв-" + i + "-"));
        }
        assertFalse(reply.getText().contains("Отзыв-1-"));
        assertFalse(reply.getText().contains("Отзыв-2-"));
        assertTrue(reply.getText().indexOf("Отзыв-7-") < reply.getText().indexOf("Отзыв-6-"));
        assertFalse(reply.getButtons().containsKey("Далее"));
    }

    @Test
    void emptyAndInvalidReviewPages() {
        assertTrue(logic.handleUserCommand("a", "/reviews").getButtons().containsValue("/start"));
        leaveReview("a", 5, "Хорошая мойка");
        for (String page : List.of("-1", "abc", "1000", "5")) {
            BotReply reply = logic.handleUserCommand("b", "/reviews " + page);
            assertFalse(reply.getButtons().containsKey("Далее"));
            assertFalse(reply.getText().contains("Хорошая мойка"));
        }
    }

    @Test
    void emptyLongAndUnknownCommands() {
        assertTrue(logic.handleUserCommand("a", null).getButtons().containsValue("/start"));
        assertTrue(logic.handleUserCommand("a", " ").getButtons().containsValue("/start"));
        assertTrue(logic.handleUserCommand("a", "x".repeat(1001)).getText().contains("1000"));
        assertTrue(logic.handleUserCommand("a", "/unknown").getText().contains("/start"));
        assertTrue(logic.handleUserCommand("a", "/book").getText().contains("/start"));
    }

    @Test
    void failedSaveKeepsDraftForRetry() {
        logic.handleUserCommand("a", "/review");
        logic.handleUserCommand("a", "/rating 4");
        data.close();
        BotReply reply = logic.handleUserCommand("a", "Не потерять текст");
        assertTrue(logic.isWaitingForReviewText("a"));
        assertTrue(reply.getButtons().containsValue("/cancel"));
        assertFalse(reply.getText().contains("SQLException"));
    }
}
