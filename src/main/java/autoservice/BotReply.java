package autoservice;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// Текст и кнопки одного ответа. Подходит и консоли, и Telegram.
public class BotReply {
    private final String text;
    private final Map<String, String> buttons = new LinkedHashMap<>();
    private boolean mainMenu;

    public BotReply(String text) {
        this.text = text;
    }

    public void addButton(String title, String command) {
        buttons.put(title, command);
    }

    public String getText() { return text; }

    public boolean isMainMenu() { return mainMenu; }

    public void setMainMenu(boolean mainMenu) {
        this.mainMenu = mainMenu;
    }

    public Map<String, String> getButtons() {
        return Collections.unmodifiableMap(buttons);
    }

    public static BotReply withMenu(String text) {
        BotReply reply = new BotReply(text);
        reply.addButton("Главное меню", "/start");
        return reply;
    }
}
