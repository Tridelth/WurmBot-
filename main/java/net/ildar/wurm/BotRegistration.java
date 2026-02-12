package net.ildar.wurm;

public class BotRegistration {
    private Class botClass;

    private String description;

    private String abbreviation;

    public BotRegistration(Class botClass, String description, String abbreviation) {
        this.botClass = botClass;
        this.description = description;
        this.abbreviation = abbreviation;
    }

    public Class getBotClass() {
        return this.botClass;
    }

    public String getDescription() {
        return this.description;
    }

    public String getAbbreviation() {
        return this.abbreviation;
    }
}
