package net.dnd5ecraft.resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiMixinGuardTest {
    @Test void healthBarInjectionChecksForALocalPlayerBeforeReadingSpectatorState() throws Exception {
        var source = Files.readString(Path.of("src/client/java/net/dnd5ecraft/client/mixin/GuiMixin.java"));
        assertTrue(Pattern.compile("if\\s*\\(client\\.player != null\\s*&&[^;{}]*&& !client\\.player\\.isSpectator\\(\\)", Pattern.DOTALL)
                .matcher(source).find());
    }
}
