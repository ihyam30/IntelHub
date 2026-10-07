package com.intelhub.ai;

import java.util.function.Consumer;

public interface ChatModelClient {
    String id();
    String model();

    void stream(String systemPrompt, String userPrompt, Consumer<String> onToken) throws Exception;
}
