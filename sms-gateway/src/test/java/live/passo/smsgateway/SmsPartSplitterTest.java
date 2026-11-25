package live.passo.smsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import org.junit.Test;

public class SmsPartSplitterTest {
    @Test public void gsmShortMessageStaysSinglePart() {
        String message = "PASSO ride started: Merkato to Bole.";
        assertEquals(new ArrayList<>(java.util.Collections.singletonList(message)), SmsPartSplitter.divide(message));
    }

    @Test public void gsmConcatenatedMessagesRespectSeptetLimitAndPreserveText() {
        String message = "^".repeat(81); // Two septets per extension character.
        ArrayList<String> parts = SmsPartSplitter.divide(message);
        assertEquals(2, parts.size());
        assertEquals(message, String.join("", parts));
        assertTrue(parts.get(0).length() <= 76);
    }

    @Test public void unicodeUsesUcs2LimitsAndPreservesSurrogatePairs() {
        String amharic = "አ".repeat(71);
        ArrayList<String> parts = SmsPartSplitter.divide(amharic);
        assertEquals(2, parts.size());
        assertEquals(amharic, String.join("", parts));

        String emoji = "🚕".repeat(68);
        ArrayList<String> emojiParts = SmsPartSplitter.divide(emoji);
        assertEquals(emoji, String.join("", emojiParts));
        for (String part : emojiParts) {
            assertTrue(part.length() <= 67);
            assertTrue(!Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
    }

    @Test public void rejectsNoContentAndAllowsServiceToEnforceTenPartLimit() {
        assertTrue(SmsPartSplitter.divide("").isEmpty());
        assertEquals(11, SmsPartSplitter.divide("አ".repeat(671)).size());
    }
}
