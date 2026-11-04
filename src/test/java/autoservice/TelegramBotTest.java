package autoservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TelegramBotTest {
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


    class TestBot extends TelegramBot {
        BlockingQueue<SendMessage> sent = new LinkedBlockingQueue<>();
        BlockingQueue<EditMessageText> edited = new LinkedBlockingQueue<>();
        BlockingQueue<SendPhoto> photos = new LinkedBlockingQueue<>();
        BlockingQueue<DeleteMessage> deleted = new LinkedBlockingQueue<>();
        BlockingQueue<EditMessageCaption> captions = new LinkedBlockingQueue<>();
        String callbackId;

        @Override
        protected void sendPhoto(SendPhoto photo) {
            photos.add(photo);
        }

        @Override
        protected void deleteMessage(DeleteMessage message) {
            deleted.add(message);
        }

        @Override
        protected void editCaption(EditMessageCaption caption) {
            captions.add(caption);
        }

        TestBot(BotLogic logic) {
            super("sample_bot", "123:test", logic);
        }

        @Override
        protected void sendMessage(SendMessage message) {
            sent.add(message);
        }

        @Override
        protected void editMessage(EditMessageText message) {
            edited.add(message);
        }

        @Override
        protected void answerCallback(String id) {
            callbackId = id;
        }
    }

    private Update message(String text, String chatType) {
        Chat chat = new Chat();
        chat.setId(7L);
        chat.setType(chatType);
        Message message = new Message();
        message.setChat(chat);
        message.setMessageId(1);
        message.setText(text);
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    @Test
    void messageAndCallbackUseTheSameLogic() throws Exception {
        BotLogic logic = new BotLogic(data);
        try (TestBot bot = new TestBot(logic)) {
            assertEquals("sample_bot", bot.getBotUsername());
            bot.onUpdateReceived(message("/start@sample_bot", "private"));
            SendPhoto menu = bot.photos.poll(2, TimeUnit.SECONDS);
            assertNotNull(menu);
            assertEquals("7", menu.getChatId());
            assertTrue(menu.getCaption().contains("Добро пожаловать"));
            assertNull(menu.getParseMode());
            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) menu.getReplyMarkup();
            assertEquals(4, keyboard.getKeyboard().size());
            assertEquals("/services", keyboard.getKeyboard().get(0).get(0).getCallbackData());

            CallbackQuery callback = new CallbackQuery();
            callback.setId("callback1");
            callback.setMessage(message("old", "private").getMessage());
            callback.setData("/help");
            Update update = new Update();
            update.setCallbackQuery(callback);
            bot.onUpdateReceived(update);
            SendPhoto changed = bot.photos.poll(2, TimeUnit.SECONDS);
            assertNotNull(changed);
            assertEquals("7", changed.getChatId());
            assertTrue(changed.getCaption().contains("Добро пожаловать"));
            assertEquals(4, ((InlineKeyboardMarkup) changed.getReplyMarkup()).getKeyboard().size());
            DeleteMessage removed = bot.deleted.poll(2, TimeUnit.SECONDS);
            assertNotNull(removed);
            assertEquals(1, removed.getMessageId());
            assertTrue(bot.sent.isEmpty());
            assertEquals("callback1", bot.callbackId);
        }
    }

    private Update callback(String command, int messageId) {
        Message oldMessage = message("старое меню", "private").getMessage();
        oldMessage.setMessageId(messageId);
        CallbackQuery query = new CallbackQuery();
        query.setId("callback-" + messageId);
        query.setMessage(oldMessage);
        query.setData(command);
        Update update = new Update();
        update.setCallbackQuery(query);
        return update;
    }

    private Update photoCallback(String command, int messageId) {
        Update update = callback(command, messageId);
        PhotoSize photo = new PhotoSize();
        photo.setFileId("test-photo");
        photo.setWidth(1206);
        photo.setHeight(1206);
        ((Message) update.getCallbackQuery().getMessage()).setPhoto(java.util.List.of(photo));
        return update;
    }

    @Test
    void photoIsBundledWithApplication() throws Exception {
        try (java.io.InputStream image = TelegramBot.class.getResourceAsStream("/menu.jpg")) {
            assertNotNull(image);
            assertEquals(0xFF, image.read());
            assertEquals(0xD8, image.read());
            assertTrue(image.readAllBytes().length > 1000);
        }
    }

    @Test
    void menuPhotoChangesToTextSectionAndBack() throws Exception {
        try (TestBot bot = new TestBot(new BotLogic(data))) {
            bot.onUpdateReceived(photoCallback("/services", 10));
            SendMessage services = bot.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(services);
            assertTrue(services.getText().contains("Каталог услуг"));
            DeleteMessage oldPhoto = bot.deleted.poll(2, TimeUnit.SECONDS);
            assertNotNull(oldPhoto);
            assertEquals(10, oldPhoto.getMessageId());

            bot.onUpdateReceived(callback("/start", 11));
            SendPhoto menu = bot.photos.poll(2, TimeUnit.SECONDS);
            assertNotNull(menu);
            assertTrue(menu.getCaption().contains("EliBai"));
            assertTrue(menu.getCaption().length() <= 1024);
            assertEquals(4, ((InlineKeyboardMarkup) menu.getReplyMarkup()).getKeyboard().size());
            DeleteMessage oldText = bot.deleted.poll(2, TimeUnit.SECONDS);
            assertNotNull(oldText);
            assertEquals(11, oldText.getMessageId());

            bot.onUpdateReceived(photoCallback("/help", 12));
            EditMessageCaption caption = bot.captions.poll(2, TimeUnit.SECONDS);
            assertNotNull(caption);
            assertEquals(12, caption.getMessageId());
            assertEquals("7", caption.getChatId());
            assertTrue(bot.photos.isEmpty());
            assertTrue(bot.deleted.isEmpty());
        }
    }

    @Test
    void repeatedPhotoCaptionDoesNotCreateAnotherMessage() throws Exception {
        CountDownLatch edited = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger extraCalls = new java.util.concurrent.atomic.AtomicInteger();
        try (TelegramBot bot = new TelegramBot("sample_bot", "123:test", new BotLogic(data)) {
            @Override
            protected void answerCallback(String id) {}
            @Override
            protected void editCaption(EditMessageCaption caption) throws TelegramApiException {
                edited.countDown();
                throw new TelegramApiException("Bad Request: message is not modified");
            }
            @Override
            protected void sendPhoto(SendPhoto photo) { extraCalls.incrementAndGet(); }
            @Override
            protected void deleteMessage(DeleteMessage message) { extraCalls.incrementAndGet(); }
        }) {
            bot.onUpdateReceived(photoCallback("/start", 10));
            assertTrue(edited.await(2, TimeUnit.SECONDS));
        }
        assertEquals(0, extraCalls.get());
    }

    @Test
    void failedPhotoUploadKeepsPreviousMessage() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger deletions = new java.util.concurrent.atomic.AtomicInteger();
        try (TelegramBot bot = new TelegramBot("sample_bot", "123:test", new BotLogic(data)) {
            @Override
            protected void answerCallback(String id) {}
            @Override
            protected void sendPhoto(SendPhoto photo) throws TelegramApiException {
                attempted.countDown();
                throw new TelegramApiException("upload failed");
            }
            @Override
            protected void deleteMessage(DeleteMessage message) { deletions.incrementAndGet(); }
        }) {
            bot.onUpdateReceived(callback("/start", 10));
            assertTrue(attempted.await(2, TimeUnit.SECONDS));
        }
        assertEquals(0, deletions.get());
    }

    @Test
    void starsAreButtonsAndNextMessageBecomesReviewText() throws Exception {
        BotLogic logic = new BotLogic(data);
        try (TestBot bot = new TestBot(logic)) {
            bot.onUpdateReceived(message("/review", "private"));
            SendMessage ratingMenu = bot.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(ratingMenu);
            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) ratingMenu.getReplyMarkup();
            assertEquals(6, keyboard.getKeyboard().size());
            for (int i = 1; i <= 5; i++) {
                assertEquals("⭐".repeat(i), keyboard.getKeyboard().get(i - 1).get(0).getText());
                assertEquals("/rating " + i, keyboard.getKeyboard().get(i - 1).get(0).getCallbackData());
            }
            bot.onUpdateReceived(callback("/rating 4", 10));
            EditMessageText prompt = bot.edited.poll(2, TimeUnit.SECONDS);
            assertNotNull(prompt);
            assertEquals(10, prompt.getMessageId());
            assertTrue(prompt.getText().contains("⭐⭐⭐⭐"));
            assertTrue(logic.isWaitingForReviewText("tg:7"));
            assertTrue(data.getReviews().isEmpty());
            bot.onUpdateReceived(message("Хорошая мойка", "private"));
            SendMessage saved = bot.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(saved);
            assertEquals(java.util.List.of("4/5 — Хорошая мойка"), data.getReviews());
            assertFalse(logic.isWaitingForReviewText("tg:7"));
            assertEquals("7", saved.getChatId());
        }
    }

    @Test
    void expiredCallbackAnswerDoesNotLoseRatingOrReviewText() throws Exception {
        BotLogic logic = new BotLogic(data);
        BlockingQueue<EditMessageText> edited = new LinkedBlockingQueue<>();
        BlockingQueue<SendMessage> sent = new LinkedBlockingQueue<>();
        try (TelegramBot bot = new TelegramBot("sample_bot", "123:test", logic) {
            @Override
            protected void answerCallback(String id) throws TelegramApiException {
                throw new TelegramApiException("Bad Request: query is too old and response timeout expired");
            }
            @Override
            protected void editMessage(EditMessageText message) {
                edited.add(message);
            }
            @Override
            protected void sendMessage(SendMessage message) {
                sent.add(message);
            }
        }) {
            bot.onUpdateReceived(callback("/review", 10));
            assertNotNull(edited.poll(2, TimeUnit.SECONDS));
            bot.onUpdateReceived(callback("/rating 5", 10));
            assertNotNull(edited.poll(2, TimeUnit.SECONDS));
            assertTrue(logic.isWaitingForReviewText("tg:7"));
            bot.onUpdateReceived(message("четкий автосервис", "private"));
            assertNotNull(sent.poll(2, TimeUnit.SECONDS));
            assertEquals(java.util.List.of("5/5 — четкий автосервис"), data.getReviews());
        }
    }

    @Test
    void failedEditSendsPromptAndReviewCanStillBeSaved() throws Exception {
        BotLogic logic = new BotLogic(data);
        BlockingQueue<SendMessage> sent = new LinkedBlockingQueue<>();
        try (TelegramBot bot = new TelegramBot("sample_bot", "123:test", logic) {
            @Override
            protected void answerCallback(String id) {}
            @Override
            protected void editMessage(EditMessageText message) throws TelegramApiException {
                throw new TelegramApiException("Bad Request: message to edit not found");
            }
            @Override
            protected void sendMessage(SendMessage message) {
                sent.add(message);
            }
        }) {
            bot.onUpdateReceived(callback("/review", 10));
            assertNotNull(sent.poll(2, TimeUnit.SECONDS));
            bot.onUpdateReceived(callback("/rating 4", 10));
            SendMessage prompt = sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(prompt);
            assertTrue(prompt.getText().contains("⭐⭐⭐⭐"));
            assertTrue(logic.isWaitingForReviewText("tg:7"));
            bot.onUpdateReceived(message("четкий автосервис", "private"));
            assertNotNull(sent.poll(2, TimeUnit.SECONDS));
            assertEquals(java.util.List.of("4/5 — четкий автосервис"), data.getReviews());
        }
    }

    @Test
    void errorOutputDoesNotExposeBotToken() throws Exception {
        java.io.PrintStream oldError = System.err;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        BotLogic logic = new BotLogic(data);
        CountDownLatch attempted = new CountDownLatch(1);
        try {
            System.setErr(new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
            try (TelegramBot bot = new TelegramBot("sample_bot", "123:secret", logic) {
                @Override
                protected void sendMessage(SendMessage message) throws TelegramApiException {
                    attempted.countDown();
                    throw new TelegramApiException("https://api.telegram.org/bot123:secret/sendMessage");
                }
            }) {
                bot.onUpdateReceived(message("/services", "private"));
                assertTrue(attempted.await(2, TimeUnit.SECONDS));
            }
            String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("отправка ответа"));
            assertFalse(text.contains("123:secret"));
            assertFalse(text.contains("api.telegram.org"));
        } finally {
            System.setErr(oldError);
        }
    }

    @Test
    void unchangedEditDoesNotStopNextCallback() throws Exception {
        BotLogic logic = new BotLogic(data);
        BlockingQueue<EditMessageText> edits = new LinkedBlockingQueue<>();
        try (TelegramBot bot = new TelegramBot("sample_bot", "123:test", logic) {
            int count;
            @Override
            protected void answerCallback(String id) {}
            @Override
            protected void editMessage(EditMessageText message) throws TelegramApiException {
                count++;
                if (count == 1) {
                    throw new TelegramApiException("Bad Request: message is not modified");
                }
                edits.add(message);
            }
        }) {
            bot.onUpdateReceived(callback("/services", 1));
            bot.onUpdateReceived(callback("/services", 2));
            EditMessageText edited = edits.poll(2, TimeUnit.SECONDS);
            assertNotNull(edited);
            assertEquals(2, edited.getMessageId());
        }
    }

    @Test
    void ignoresGroupsOtherBotsAndNonText() {
        BotLogic logic = new BotLogic(data);
        TestBot bot = new TestBot(logic);
        try (bot) {
            bot.onUpdateReceived(message("/start", "group"));
            bot.onUpdateReceived(message("/start@other_bot", "private"));
            bot.onUpdateReceived(message(null, "private"));
            bot.onUpdateReceived(new Update());
            CallbackQuery callback = new CallbackQuery();
            callback.setId("without_message");
            Update update = new Update();
            update.setCallbackQuery(callback);
            bot.onUpdateReceived(update);
        }
        assertTrue(bot.sent.isEmpty());
    }

    @Test
    void processingIsAsync() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        BotLogic logic = new BotLogic(data) {
            @Override
            public BotReply handleUserCommand(String client, String text) {
                worker.set(Thread.currentThread());
                entered.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return BotReply.withMenu(client);
            }
        };
        try (TestBot bot = new TestBot(logic)) {
            bot.onUpdateReceived(message("/slow", "private"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), worker.get());
            assertTrue(bot.sent.isEmpty());
            release.countDown();
            SendMessage reply = bot.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(reply);
            assertEquals("tg:7", reply.getText());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedSendDoesNotStopNextUpdate() throws Exception {
        BotLogic logic = new BotLogic(data);
        CountDownLatch sent = new CountDownLatch(1);
        try (TelegramBot bot = new TelegramBot("sample_bot", "123:test", logic) {
            int count;
            @Override
            protected void sendMessage(SendMessage message) throws TelegramApiException {
                count++;
                if (count == 1) {
                    throw new TelegramApiException("test");
                }
                sent.countDown();
            }
        }) {
            bot.onUpdateReceived(message("/services", "private"));
            bot.onUpdateReceived(message("/services", "private"));
            assertTrue(sent.await(2, TimeUnit.SECONDS));
        }
    }
}
