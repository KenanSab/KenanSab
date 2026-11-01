package warfare.core;

import java.io.Serializable;
import java.util.List;

public final class GameEvent implements Serializable {
    public final String id, title, text;
    public final List<String> options;
    public final int nation;

    public GameEvent(String id, int nation, String title, String text, List<String> options) {
        this.id = id; this.nation = nation; this.title = title; this.text = text; this.options = options;
    }
}
