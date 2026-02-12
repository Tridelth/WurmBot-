package net.ildar.wurm;

import com.wurmonline.shared.util.MulticolorLineSegment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public class Chat {
    private static List<MessageProcessor> messageProcessors = new ArrayList<>();

    public static MessageProcessor registerMessageProcessor(String tabName, Function<String, Boolean> filter, Runnable callback) {
        MessageProcessor messageProcessor = new MessageProcessor(tabName, filter, callback);
        messageProcessors.add(messageProcessor);
        return messageProcessor;
    }

    public static void unregisterMessageProcessor(MessageProcessor messageProcessor) {
        messageProcessors.remove(messageProcessor);
    }

    public static void onMessage(String context, Object input, boolean silent) {
        String message;
        if (input instanceof List) {
            message = pruneMulticolorString((List<MulticolorLineSegment>)input);
        } else {
            message = (String)input;
        }
        String messageWithoutTime = message.substring(11).trim();
        if (messageWithoutTime.isEmpty())
            return;
        messageProcessors.stream()
                .filter(mp -> Objects.equals(mp.tabName, context))
                .filter(mp -> ((Boolean)mp.filter.apply(message)).booleanValue())
                .forEach(mp -> mp.callback.run());
        switch (context) {
            case ":Combat":
                if (input instanceof List)
                    modifyCombatMessage((List<MulticolorLineSegment>)input);
                break;
        }
    }

    private static String pruneMulticolorString(List<MulticolorLineSegment> multicolorString) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<MulticolorLineSegment> iter = multicolorString.iterator(); iter.hasNext(); ) {
            MulticolorLineSegment segment = iter.next();
            sb.append(segment.getText());
        }
        return sb.toString();
    }

    private static void modifyCombatMessage(List<MulticolorLineSegment> segments) {
        for (Iterator<MulticolorLineSegment> iter = segments.iterator(); iter.hasNext(); ) {
            MulticolorLineSegment segment = iter.next();
            if (segment.getText().startsWith(" targets your")) {
                iter.remove();
                MulticolorLineSegment newsegment = new MulticolorLineSegment(" targets ", (byte)0);
                segments.add(newsegment);
                newsegment = new MulticolorLineSegment(segment.getText().substring(9), (byte)12);
                segments.add(newsegment);
                break;
            }
        }
    }

    public static class MessageProcessor {
        public String tabName;

        public Function<String, Boolean> filter;

        public Runnable callback;

        public MessageProcessor(String tabName, Function<String, Boolean> filter, Runnable callback) {
            this.tabName = tabName;
            this.filter = filter;
            this.callback = callback;
        }
    }
}
