package autoservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigAndConsoleTest {
    @TempDir Path directory;
    BotData data;

    @org.junit.jupiter.api.BeforeEach
    void prepareDatabase() {
        data = new BotData(directory.resolve("bot"));
    }

    @AfterEach
    void closeDatabase() {
        data.close();
    }

    @ParameterizedTest
    @CsvSource({"console,true,false", "tg,false,true", "tgANDconsole,true,true"})
    void modes(String mode, boolean console, boolean telegram) {
        BotConfig config = new BotConfig(new String[]{mode}, Map.of(
                "TG_BOT_TOKEN", "123:test", "TG_BOT_USERNAME", "sample_bot"));
        assertEquals(console, config.useConsole());
        assertEquals(telegram, config.useTelegram());
        assertEquals("123:test", config.getToken());
        assertEquals("sample_bot", config.getUsername());
    }

    @Test
    void wrongConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new BotConfig(new String[0], Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new BotConfig(new String[]{"x"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new BotConfig(new String[]{"tg", "console"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new BotConfig(new String[]{"tg"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new BotConfig(new String[]{"tg"}, Map.of("TG_BOT_TOKEN", "123:test")));
        assertThrows(IllegalArgumentException.class, () -> new BotConfig(new String[]{"tg"}, Map.of(
                "TG_BOT_TOKEN", "123:test", "TG_BOT_USERNAME", "@sample_bot")));
        assertNotNull(new BotConfig(new String[]{"console"}, Map.of()));
    }

    @Test
    void errorNamesMissingVariableAndDoesNotPrintToken() {
        IllegalArgumentException missingUsername = assertThrows(IllegalArgumentException.class,
                () -> new BotConfig(new String[]{"tg"}, Map.of(
                        "TG_BOT_TOKEN", "123:example", " TG_BOT_USERNAME", "sample_bot")));
        assertTrue(missingUsername.getMessage().contains("TG_BOT_USERNAME"));
        assertFalse(missingUsername.getMessage().contains("123:example"));
        IllegalArgumentException missingToken = assertThrows(IllegalArgumentException.class,
                () -> new BotConfig(new String[]{"tg"}, Map.of("TG_BOT_USERNAME", "sample_bot")));
        assertTrue(missingToken.getMessage().contains("TG_BOT_TOKEN"));
    }

    @Test
    void consoleAcceptsNumbersCommandsAndExit() throws Exception {
        BotLogic logic = new BotLogic(data);
        StringWriter output = new StringWriter();
        ConsoleBot bot = new ConsoleBot(logic,
                new StringReader("1\n/start\n2\n/start\n4\n5\nХорошая мойка\n/reviews\n/exit\n"), output);
        bot.run();
        assertTrue(output.toString().contains("Каталог услуг"));
        assertTrue(output.toString().contains("0 бонусов"));
        assertTrue(output.toString().contains("5/5 — Хорошая мойка"));
        assertEquals(java.util.List.of("5/5 — Хорошая мойка"), data.getReviews());
        new ConsoleBot(logic, new StringReader(""), new StringWriter()).run();
    }

    @Test
    void invalidMenuNumbersDoNotExecuteCommands() throws Exception {
        java.util.List<String> commands = new java.util.ArrayList<>();
        BotLogic logic = new BotLogic(data) {
            @Override
            public synchronized BotReply handleUserCommand(String client, String text) {
                commands.add(text);
                return super.handleUserCommand(client, text);
            }
        };
        new ConsoleBot(logic, new StringReader("0\n99\n1\n/exit\n"), new StringWriter()).run();
        assertEquals(java.util.List.of("/start", "/services"), commands);
    }

    @Test
    void numericReviewTextIsNotTreatedAsMenuNumber() throws Exception {
        BotLogic logic = new BotLogic(data);
        new ConsoleBot(logic, new StringReader("4\n3\n123\n/exit\n"), new StringWriter()).run();
        assertEquals(java.util.List.of("3/5 — 123"), data.getReviews());
    }

    @Test
    void reviewCanBeCancelledFromConsole() throws Exception {
        BotLogic logic = new BotLogic(data);
        new ConsoleBot(logic, new StringReader("4\n5\n/cancel\n/exit\n"), new StringWriter()).run();
        assertTrue(data.getReviews().isEmpty());
        assertFalse(logic.isWaitingForReviewText("console:local"));
    }

    @Test
    void mainStartsConsoleWithoutToken() throws Exception {
        InputStream oldInput = System.in;
        PrintStream oldOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream("/exit\n".getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            Main.run(new BotConfig(new String[]{"console"}, Map.of()), data);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Добро пожаловать"));
        } finally {
            System.setIn(oldInput);
            System.setOut(oldOutput);
        }
    }
}
