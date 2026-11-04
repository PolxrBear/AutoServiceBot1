package autoservice;

import java.util.Map;

public class BotConfig {
    private final String mode;
    private final String token;
    private final String username;

    public BotConfig(String[] args, Map<String, String> environment) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Укажите режим: console, tg или tgANDconsole");
        }
        mode = args[0];
        if (!mode.equals("console") && !mode.equals("tg") && !mode.equals("tgANDconsole")) {
            throw new IllegalArgumentException("Неизвестный режим: " + mode);
        }

        token = environment.getOrDefault("TG_BOT_TOKEN", "").trim();
        username = environment.getOrDefault("TG_BOT_USERNAME", "").trim();
        if (useTelegram() && token.isEmpty()) {
            throw new IllegalArgumentException("Не задана TG_BOT_TOKEN");
        }
        if (useTelegram() && username.isEmpty()) {
            throw new IllegalArgumentException("Не задана TG_BOT_USERNAME");
        }
        if (useTelegram() && username.startsWith("@")) {
            throw new IllegalArgumentException("Укажите username без @");
        }
    }

    public boolean useTelegram() { return !mode.equals("console"); }
    public boolean useConsole() { return !mode.equals("tg"); }
    public String getToken() { return token; }
    public String getUsername() { return username; }
}
