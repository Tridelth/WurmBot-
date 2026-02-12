package net.ildar.wurm;

import java.util.Objects;

public class Pair<Key, Value> {
    private Key key;

    private Value value;

    public Pair(Key key, Value value) {
        this.key = key;
        this.value = value;
    }

    public Key getKey() {
        return this.key;
    }

    public Value getValue() {
        return this.value;
    }

    public int hashCode() {
        return Objects.hash(new Object[] { this.key, this.value });
    }

    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        Pair pair = (Pair)obj;
        if (pair == null)
            return false;
        return (Objects.equals(this.key, pair.key) && Objects.equals(this.value, pair.value));
    }
}
