package com.itzixi.evaluation.prompt;

import com.itzixi.evaluation.config.EvaluationConfigLoader.LoadedEntry;
import com.itzixi.evaluation.model.EvaluationModels.*;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Uses the original SYSTEM block unchanged; the Modelfile FROM is provenance,
 * not an API model ID.
 */
public final class PromptBuilder {
    public BuiltPrompt build(LoadedEntry entry, HaiCase testCase) {
        try {
            byte[] bytes = Files.readAllBytes(entry.promptPath());
            String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            var system = Pattern.compile("(?ms)^SYSTEM[ \\t]+\"\"\"\\s*\\R?(.*?)\"\"\"").matcher(source);
            if (!system.find())
                throw new IllegalArgumentException("Missing SYSTEM block: " + entry.promptPath());
            String content = system.group(1).strip();
            if (content.isBlank() || system.find())
                throw new IllegalArgumentException("Expected exactly one nonempty SYSTEM block");
            var model = Pattern.compile("(?m)^FROM[ \\t]+([^\\r\\n]+)").matcher(source);
            if (!model.find())
                throw new IllegalArgumentException("Missing FROM in prompt source");
            String sourceModel = model.group(1).strip();
            var temperature = Pattern.compile("(?m)^PARAMETER[ \\t]+temperature[ \\t]+([0-9.]+)[ \\t]*$")
                    .matcher(source);
            if (!temperature.find())
                throw new IllegalArgumentException("Missing temperature in prompt source");
            double value = Double.parseDouble(temperature.group(1));
            if (!Double.isFinite(value) || value < 0 || value > 2)
                throw new IllegalArgumentException("Invalid prompt temperature");
            return new BuiltPrompt(entry.promptPath(), sha256(bytes), content, sourceModel, value,
                    List.of(new Message("system", content), new Message("user", testCase.struggle())));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read prompt source", exception);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
