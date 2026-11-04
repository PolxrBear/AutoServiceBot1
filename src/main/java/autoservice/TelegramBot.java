package autoservice;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TelegramBot extends TelegramLongPollingBot implements AutoCloseable {
    private final String username;
    private final BotLogic logic;

    // Отдельный поток обрабатывает команды по очереди, пока Telegram принимает новые.
    // Если очередь заполнена, задача выполняется в принимающем потоке.
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy());

    public TelegramBot(String username, String token, BotLogic logic) {
        super(token);
        this.username = username;
        this.logic = logic;
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public void onUpdateReceived(Update update) {
        executor.submit(() -> handleUpdate(update));
    }

    private void handleUpdate(Update update) {
        try {
            long chatId;
            Integer messageId = null;
            boolean hasPhoto = false;
            String text;
            if (update.hasCallbackQuery()) {
                try {
                    answerCallback(update.getCallbackQuery().getId());
                } catch (TelegramApiException e) {
                    reportError("подтверждение кнопки", e);
                }
                if (update.getCallbackQuery().getMessage() == null
                        || !update.getCallbackQuery().getMessage().isUserMessage()) {
                    return;
                }
                chatId = update.getCallbackQuery().getMessage().getChatId();
                messageId = update.getCallbackQuery().getMessage().getMessageId();
                if (update.getCallbackQuery().getMessage() instanceof Message) {
                    Message original = (Message) update.getCallbackQuery().getMessage();
                    hasPhoto = original.hasPhoto();
                }
                text = update.getCallbackQuery().getData();
            } else if (update.hasMessage() && update.getMessage().hasText()
                    && update.getMessage().isUserMessage()) {
                chatId = update.getMessage().getChatId();
                text = update.getMessage().getText();
            } else {
                return;
            }
            // /start@имя_бота тоже робит
            if (text != null && text.startsWith("/")) {
                String[] parts = text.split("\\s+", 2);
                int at = parts[0].indexOf('@');
                if (at >= 0) {
                    if (!parts[0].substring(at + 1).equalsIgnoreCase(username)) {
                        return;
                    }
                    text = parts[0].substring(0, at);
                    if (parts.length == 2) {
                        text += " " + parts[1];
                    }
                }
            }
            BotReply reply = logic.handleUserCommand("tg:" + chatId, text);
            if (messageId == null) {
                sendReply(chatId, reply);
            } else if (hasPhoto != reply.isMainMenu()) {
                replaceReply(chatId, messageId, reply);
            } else if (hasPhoto) {
                editPhotoCaption(chatId, messageId, reply);
            } else {
                editReply(chatId, messageId, reply);
            }
        } catch (TelegramApiException e) {
            reportError("отправка ответа", e);
        } catch (RuntimeException e) {
            reportError("обработка команды", e);
        }
    }

    private void sendReply(long chatId, BotReply reply) throws TelegramApiException {
        if (!reply.isMainMenu()) {
            sendMessage(createMessage(chatId, reply));
            return;
        }
        try (InputStream image = TelegramBot.class.getResourceAsStream("/menu.jpg")) {
            if (image == null) {
                throw new IllegalStateException("Не найдено фото главного меню");
            }
            SendPhoto photo = new SendPhoto();
            photo.setChatId(String.valueOf(chatId));
            photo.setPhoto(new InputFile(image, "menu.jpg"));
            photo.setCaption(reply.getText());
            photo.setReplyMarkup(createKeyboard(reply));
            sendPhoto(photo);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать фото главного меню", e);
        }
    }

    private void replaceReply(long chatId, int messageId, BotReply reply) throws TelegramApiException {
        sendReply(chatId, reply);
        try {
            deleteMessage(new DeleteMessage(String.valueOf(chatId), messageId));
        } catch (TelegramApiException e) {
            reportError("удаление старого сообщения", e);
        }
    }

    private void editPhotoCaption(long chatId, int messageId, BotReply reply) throws TelegramApiException {
        EditMessageCaption caption = new EditMessageCaption();
        caption.setChatId(String.valueOf(chatId));
        caption.setMessageId(messageId);
        caption.setCaption(reply.getText());
        caption.setReplyMarkup(createKeyboard(reply));
        try {
            editCaption(caption);
        } catch (TelegramApiException e) {
            if (e.getMessage() == null || !e.getMessage().contains("message is not modified")) {
                reportError("редактирование подписи к фото", e);
                replaceReply(chatId, messageId, reply);
            }
        }
    }

    private InlineKeyboardMarkup createKeyboard(BotReply reply) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Map.Entry<String, String> button : reply.getButtons().entrySet()) {
            InlineKeyboardButton telegramButton = new InlineKeyboardButton();
            telegramButton.setText(button.getKey());
            telegramButton.setCallbackData(button.getValue());
            List<InlineKeyboardButton> row = new ArrayList<>();
            row.add(telegramButton);
            rows.add(row);
        }
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private SendMessage createMessage(long chatId, BotReply reply) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(reply.getText());
        message.setReplyMarkup(createKeyboard(reply));
        return message;
    }

    private void editReply(long chatId, int messageId, BotReply reply) throws TelegramApiException {
        EditMessageText message = new EditMessageText();
        message.setChatId(String.valueOf(chatId));
        message.setMessageId(messageId);
        message.setText(reply.getText());
        message.setReplyMarkup(createKeyboard(reply));
        try {
            editMessage(message);
        } catch (TelegramApiException e) {
            if (e.getMessage() == null || !e.getMessage().contains("message is not modified")) {
                reportError("редактирование сообщения", e);
                sendMessage(createMessage(chatId, reply));
            }
        }
    }

    private void reportError(String operation, Exception error) {
        // не печатаем исключение целиком, тк URL запроса может содержать токен.
        String reason = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message != null && (message.contains("query is too old") || message.contains("query ID is invalid"))) {
            reason = "истекло время подтверждения кнопки";
        } else if (message != null && message.contains("message to edit not found")) {
            reason = "исходное сообщение не найдено";
        }
        System.err.println("Telegram, " + operation + ": " + reason);
    }

    protected void sendMessage(SendMessage message) throws TelegramApiException {
        execute(message);
    }

    protected void sendPhoto(SendPhoto photo) throws TelegramApiException {
        execute(photo);
    }

    protected void deleteMessage(DeleteMessage message) throws TelegramApiException {
        execute(message);
    }

    protected void editCaption(EditMessageCaption caption) throws TelegramApiException {
        execute(caption);
    }

    protected void editMessage(EditMessageText message) throws TelegramApiException {
        execute(message);
    }

    protected void answerCallback(String id) throws TelegramApiException {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(id);
        execute(answer);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
