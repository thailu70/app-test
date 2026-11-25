package live.passo.smsgateway;

import java.util.ArrayList;

/** Local GSM 03.38 / UCS-2 segmentation; never calls the telephony service. */
final class SmsPartSplitter {
    private static final String GSM_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡"
                    + "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXTENSION = "\f^{}\\[~]|€";

    private SmsPartSplitter() {}

    static ArrayList<String> divide(String message) {
        if (message == null || message.isEmpty()) return new ArrayList<>();
        return isDefaultGsm(message) ? divideGsm(message) : divideUnicode(message);
    }

    private static boolean isDefaultGsm(String message) {
        for (int offset = 0; offset < message.length();) {
            int codePoint = message.codePointAt(offset);
            if (!containsCodePoint(GSM_BASIC, codePoint) && !containsCodePoint(GSM_EXTENSION, codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean containsCodePoint(String values, int codePoint) {
        return values.indexOf(codePoint) >= 0;
    }

    private static int gsmSeptets(int codePoint) {
        return containsCodePoint(GSM_EXTENSION, codePoint) ? 2 : 1;
    }

    private static ArrayList<String> divideGsm(String message) {
        int totalSeptets = 0;
        for (int offset = 0; offset < message.length();) {
            int codePoint = message.codePointAt(offset);
            totalSeptets += gsmSeptets(codePoint);
            offset += Character.charCount(codePoint);
        }
        int limit = totalSeptets <= 160 ? 160 : 153;
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < message.length();) {
            int codePoint = message.codePointAt(offset);
            int units = gsmSeptets(codePoint);
            if (used + units > limit) {
                parts.add(part.toString());
                part.setLength(0);
                used = 0;
            }
            part.appendCodePoint(codePoint);
            used += units;
            offset += Character.charCount(codePoint);
        }
        if (part.length() > 0) parts.add(part.toString());
        return parts;
    }

    private static ArrayList<String> divideUnicode(String message) {
        int limit = message.length() <= 70 ? 70 : 67;
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < message.length();) {
            int codePoint = message.codePointAt(offset);
            int units = Character.charCount(codePoint);
            if (used + units > limit) {
                parts.add(part.toString());
                part.setLength(0);
                used = 0;
            }
            part.appendCodePoint(codePoint);
            used += units;
            offset += units;
        }
        if (part.length() > 0) parts.add(part.toString());
        return parts;
    }
}
