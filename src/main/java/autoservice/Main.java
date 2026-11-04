package autoservice;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

public class Main {
    public static void main(String[] args) {
        try {
            BotConfig config = new BotConfig(args, System.getenv());
            run(config);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Не удалось запустить бота. Проверьте настройки и сеть.");
        }
    }

    static void run(BotConfig config) throws Exception {
        try (BotData data = new BotData()) {
            run(config, data);
        }
    }

    static void run(BotConfig config, BotData data) throws Exception {
        BotLogic logic = new BotLogic(data);
        TelegramBot telegramBot = null;
        BotSession session = null;
        Thread shutdownHook = null;
        try {
            if (config.useTelegram()) {
                telegramBot = new TelegramBot(config.getUsername(), config.getToken(), logic);
                TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
                session = api.registerBot(telegramBot);
                TelegramBot botToStop = telegramBot;
                BotSession sessionToStop = session;
                shutdownHook = new Thread(() -> {
                    sessionToStop.stop();
                    botToStop.close();
                });
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            }
            if (config.useConsole()) {
                ConsoleBot consoleBot = new ConsoleBot(logic,
                        new InputStreamReader(System.in, StandardCharsets.UTF_8),
                        new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
                consoleBot.run();
            } else {
                // Режим tg работает до остановки программы.
                new CountDownLatch(1).await();
            }
        } finally {
            if (session != null) {
                session.stop();
            }
            if (telegramBot != null) {
                telegramBot.close();
            }
            if (shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        }
    }
}
