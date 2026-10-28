package org.monolites.monolit.services;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DialogueModeService {

    public enum Mode {
        DEFAULT,
        CHATGPT
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.DEFAULT);

    public Mode currentMode() {
        return mode.get();
    }

    public void setMode(Mode mode) {
        this.mode.set(Objects.requireNonNull(mode));
    }
}
