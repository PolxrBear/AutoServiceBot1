package autoservice;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

// Общая логика. Здесь нет классов Telegram и ввода из консоли.
public class BotLogic {
    private final BotData data;
    // 0 — клиент выбирает звёзды, 1–5 — ждём текст отзыва с этой оценкой.
    private final Map<String, Integer> reviewRatings = new HashMap<>();

    public BotLogic(BotData data) {
        this.data = data;
    }

    public synchronized BotReply handleUserCommand(String clientId, String text) {
        if (text == null || text.isBlank()) {
            if (isWaitingForReviewText(clientId)) {
                return reviewTextReply("Напишите текст отзыва");
            }
            return BotReply.withMenu("Введите /start");
        }
        if (text.length() > 1000) {
            if (isWaitingForReviewText(clientId)) {
                return reviewTextReply("Текст отзыва должен содержать от 3 до 1000 символов");
            }
            return BotReply.withMenu("Максмум 1000 символов");
        }

        String[] parts = text.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String arguments = "";
        if (parts.length == 2) {
            arguments = parts[1];
        }

        try {
            if (!command.startsWith("/") && reviewRatings.containsKey(clientId)) {
                if (!isWaitingForReviewText(clientId)) {
                    return ratingButtons("Сначала выберите оценку");
                }
                return saveReviewText(clientId, text.trim());
            }
            switch (command) {
                case "/start":
                case "/help":
                case "/cancel":
                    reviewRatings.remove(clientId);
                    return showMenu();
                case "/services":
                    reviewRatings.remove(clientId);
                    return showServices();
                case "/balance":
                    reviewRatings.remove(clientId);
                    return BotReply.withMenu("Ваш баланс: " + data.getBalance(clientId) + " бонусов.");
                case "/reviews":
                    reviewRatings.remove(clientId);
                    return showReviews(arguments);
                case "/review":
                    reviewRatings.put(clientId, 0);
                    return ratingButtons("Выберите оценку от 1 до 5");
                case "/rating":
                    return chooseRating(clientId, arguments);
                default:
                    return BotReply.withMenu("Неизвестная команда. Введите /start");
            }
        } catch (IllegalStateException e) {
            if (isWaitingForReviewText(clientId)) {
                return reviewTextReply("Не получилось сохранить отзыв. Попробуйте ещё раз");
            }
            return BotReply.withMenu("Не получилось прочитать или сохранить данные");
        }
    }

    private BotReply showMenu() {
        BotReply reply = new BotReply("Добро пожаловать в автосервис EliBai!\nВыберите действие");
        reply.setMainMenu(true);
        reply.addButton("Услуги и цены", "/services");
        reply.addButton("Баланс бонусов", "/balance");
        reply.addButton("Отзывы", "/reviews");
        reply.addButton("Оставить отзыв", "/review");
        return reply;
    }

    private BotReply showServices() {
        StringBuilder text = new StringBuilder("Каталог услуг:\n");
        for (Map.Entry<String, Integer> service : data.getServices().entrySet()) {
            text.append(service.getKey()).append(" — ").append(service.getValue()).append(" ₽\n");
        }
        return BotReply.withMenu(text.toString());
    }

    private BotReply showReviews(String arguments) {
        List<String> reviews = data.getReviews();
        if (reviews.isEmpty()) {
            return BotReply.withMenu("Отзывов пока нет");
        }
        // длинные отзывы делим на страницы
        List<String> pages = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int first = Math.max(0, reviews.size() - 5);
        for (int i = reviews.size() - 1; i >= first; i--) {
            String review = reviews.get(i) + "\n\n";
            if (text.length() + review.length() > 3500) {
                pages.add(text.toString());
                text.setLength(0);
            }
            text.append(review);
        }
        pages.add(text.toString());
        int page = 0;
        if (!arguments.isBlank()) {
            if (!arguments.matches("[0-9]{1,3}")) {
                return BotReply.withMenu("Такой страницы отзывов нет");
            }
            page = Integer.parseInt(arguments);
        }
        if (page >= pages.size()) {
            return BotReply.withMenu("Такой страницы отзывов нет");
        }
        BotReply reply = new BotReply("Последние 5 отзывов:\n" + pages.get(page));
        if (page > 0) {
            reply.addButton("Назад", "/reviews " + (page - 1));
        }
        if (page + 1 < pages.size()) {
            reply.addButton("Далее", "/reviews " + (page + 1));
        }
        reply.addButton("Главное меню", "/start");
        return reply;
    }

    public synchronized boolean isWaitingForReviewText(String clientId) {
        Integer rating = reviewRatings.get(clientId);
        return rating != null && rating > 0;
    }

    private BotReply ratingButtons(String text) {
        BotReply reply = new BotReply(text);
        for (int rating = 1; rating <= 5; rating++) {
            reply.addButton("⭐".repeat(rating), "/rating " + rating);
        }
        reply.addButton("Отменить", "/cancel");
        return reply;
    }

    private BotReply chooseRating(String clientId, String arguments) {
        if (!reviewRatings.containsKey(clientId)) {
            return BotReply.withMenu("Сначала нажмите «Оставить отзыв»");
        }
        if (!arguments.matches("[1-5]")) {
            return ratingButtons("Выберите оценку от 1 до 5");
        }
        int rating = Integer.parseInt(arguments);
        reviewRatings.put(clientId, rating);
        return reviewTextReply("Ваша оценка: " + "⭐".repeat(rating) + "\nТеперь напишите текст отзыва");
    }

    private BotReply reviewTextReply(String text) {
        BotReply reply = new BotReply(text);
        reply.addButton("Изменить оценку", "/review");
        reply.addButton("Отменить", "/cancel");
        return reply;
    }

    private BotReply saveReviewText(String clientId, String text) {
        if (text.length() < 3 || text.length() > 1000) {
            return reviewTextReply("Текст отзыва должен содержать от 3 до 1000 символов");
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return reviewTextReply("Напишите отзыв одной строкой");
            }
        }
        data.saveReview(clientId, reviewRatings.get(clientId), text);
        reviewRatings.remove(clientId);
        return BotReply.withMenu("Спасибо! Ваш отзыв опубликован");
    }
}
