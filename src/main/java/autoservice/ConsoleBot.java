package autoservice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

public class ConsoleBot {
    private final BotLogic logic;
    private final BufferedReader input;
    private final PrintWriter output;

    public ConsoleBot(BotLogic logic, Reader input, Writer output) {
        this.logic = logic;
        this.input = new BufferedReader(input);
        this.output = new PrintWriter(output, true);
    }

    public void run() throws IOException {
        BotReply reply = logic.handleUserCommand("console:local", "/start");
        showReply(reply);
        String line;
        while ((line = input.readLine()) != null) {
            line = line.trim();
            if (line.equalsIgnoreCase("/exit")) {
                break;
            }
            if (line.matches("[0-9]{1,3}") && !logic.isWaitingForReviewText("console:local")) {
                int number = Integer.parseInt(line);
                List<String> commands = new ArrayList<>(reply.getButtons().values());
                if (number < 1 || number > commands.size()) {
                    output.println("Нет такого номра команды");
                    continue;
                }
                line = commands.get(number - 1);
            }
            reply = logic.handleUserCommand("console:local", line);
            showReply(reply);
        }
    }

    private void showReply(BotReply reply) {
        output.println(reply.getText());
        if (logic.isWaitingForReviewText("console:local")) {
            output.println("Введите текст отзыва. /review — изменить оценку, /cancel — отменить");
            return;
        }
        int number = 1;
        for (String title : reply.getButtons().keySet()) {
            output.println(number + ". " + title);
            number++;
        }
        output.println("Введите номер или команду. /exit — выход");
    }
}
