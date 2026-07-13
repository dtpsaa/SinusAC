package ru.dtpsaa.sinusac.command;

import java.util.Collections;
import java.util.List;
import org.bukkit.command.CommandSender;

/**
 * Контракт одной подкоманды /sinusac.
 * <p>
 * Каждая подкоманда живёт в своей папке: command/<имя>/<Имя>Command.java
 * и реализует этот интерфейс. Регистрация — в {@link CommandRegistry}.
 */
public interface SubCommand {

    /** Имя подкоманды в нижнем регистре, например "alerts". */
    String name();

    /**
     * Выполнение подкоманды.
     *
     * @param sender кто выполнил команду
     * @param args   ПОЛНЫЙ массив аргументов /sinusac (args[0] — имя этой подкоманды)
     */
    void execute(CommandSender sender, String[] args);

    /**
     * Таб-комплит для аргументов этой подкоманды.
     * args устроен так же, как в execute (args[0] — имя подкоманды).
     */
    default List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
