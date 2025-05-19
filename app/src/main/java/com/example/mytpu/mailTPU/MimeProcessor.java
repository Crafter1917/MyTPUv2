// MimeProcessor.java
package com.example.mytpu.mailTPU;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

public class MimeProcessor {
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile("boundary=\"?(.*?)\"?$");
    private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile("Content-Type: (.*?)(;|$)");
    private static final Pattern CONTENT_ENCODING_PATTERN = Pattern.compile("Content-Transfer-Encoding: (.*?)(;|$)");

    public static List<MimePart> parseMultipart(Response response, String body) throws IOException {
        List<MimePart> parts = new ArrayList<>();
        String contentType = response.header("Content-Type", "");
        Matcher boundaryMatcher = BOUNDARY_PATTERN.matcher(contentType);

        if (!boundaryMatcher.find()) {
            // Если не multipart, обрабатываем как обычный текст
            MimePart part = new MimePart("text", "text/plain", "text");
            part.content = body;
            parts.add(part);
            return parts;
        }

        String boundary = "--" + boundaryMatcher.group(1).replace("\"", "");
        String[] mimeParts = body.split(boundary);

        for (String partStr : mimeParts) {
            if (partStr.trim().isEmpty() || partStr.startsWith("--")) continue;

            int headerEnd = partStr.indexOf("\r\n\r\n");
            if (headerEnd == -1) continue;

            String headers = partStr.substring(0, headerEnd);
            String content = partStr.substring(headerEnd + 4).trim();

            MimePart part = new MimePart(
                    extractHeaderValue(CONTENT_TYPE_PATTERN, headers),
                    extractHeaderValue(CONTENT_ENCODING_PATTERN, headers),
                    headers
            );

            part.content = content;
            parts.add(part);
        }

        return parts;
    }

    private static String extractHeaderValue(Pattern pattern, String headers) {
        Matcher m = pattern.matcher(headers);
        return m.find() ? m.group(1).trim() : "";
    }

    public static class MimePart {
        public String contentType;
        public String contentEncoding;
        public String content;
        public String headers;
        private CharSequence disposition;

        public String getFileName() {
            if (disposition == null) return "";

            Pattern pattern = Pattern.compile("filename\\*?=([^;]+)");
            Matcher m = pattern.matcher(disposition);
            if (m.find()) {
                String filename = m.group(1)
                        .replaceAll("^\"|\"$", "")
                        .replaceAll("\\\\", "");
                return filename.split(";")[0]; // Для обработки параметров вроде filename*=UTF-8''
            }
            return "";
        }

        public String getContentId() {
            Pattern pattern = Pattern.compile("Content-ID:\\s*<?([^>]+)>?");
            Matcher m = pattern.matcher(headers.toString());
            return m.find() ? m.group(1).trim() : "";
        }
        public MimePart(String contentType, String contentEncoding, String headers) {
            this.contentType = contentType.split(";")[0].trim();
            this.contentEncoding = contentEncoding;
            this.headers = headers;
        }
    }
}