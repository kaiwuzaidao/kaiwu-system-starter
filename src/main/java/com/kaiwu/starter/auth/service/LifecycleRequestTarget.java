package com.kaiwu.starter.auth.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 原始请求目标的规范校验，避免框架归一化后产生第二种授权解释。 */
final class LifecycleRequestTarget {
    /** 仅供同包断言校验器调用。 */
    private LifecycleRequestTarget() {}

    /** 返回合法原始路径，查询串仅允许阻塞查询的 cursor 和 limit。 */
    static String path(String target) throws Exception {
        require(target != null && target.length() <= 4096);
        int query = target.indexOf('?');
        String path = query < 0 ? target : target.substring(0, query);
        require(path.startsWith("/")
                && !path.endsWith("/")
                && !path.contains("//")
                && path.matches("/[A-Za-z0-9/_-]+"));
        if (query >= 0) {
            require("/internal/apps/uninstall-blockers".equals(path));
            String rawQuery = target.substring(query + 1);
            require(!rawQuery.isEmpty());
            String previous = "";
            for (String entry : rawQuery.split("&", -1)) {
                int equals = entry.indexOf('=');
                require(equals > 0);
                String name = entry.substring(0, equals);
                String raw = entry.substring(equals + 1);
                require(name.compareTo(previous) > 0);
                previous = name;
                switch (name) {
                    case "cursor" -> {
                        String value = decodeCanonical(raw);
                        require(!value.isEmpty() && value.codePointCount(0, value.length()) <= 256);
                    }
                    case "limit" -> require(raw.matches("[1-9][0-9]{0,2}") && Integer.parseInt(raw) <= 500);
                    default -> throw new IllegalArgumentException();
                }
            }
        }
        return path;
    }

    /** 严格 UTF-8 解码后重新按 RFC 3986 编码，拒绝大小写、空格和多余转义差异。 */
    private static String decodeCanonical(String value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%') {
                require(index + 2 < value.length());
                int high = Character.digit(value.charAt(++index), 16);
                int low = Character.digit(value.charAt(++index), 16);
                require(high >= 0 && low >= 0);
                bytes.write(high * 16 + low);
            } else {
                require(character < 128 && unreserved(character));
                bytes.write(character);
            }
        }
        byte[] decoded = bytes.toByteArray();
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString();
        var canonical = new StringBuilder();
        for (byte item : decoded) {
            int unsigned = Byte.toUnsignedInt(item);
            if (unreserved(unsigned)) {
                canonical.append((char) unsigned);
            } else {
                canonical
                        .append('%')
                        .append("0123456789ABCDEF".charAt(unsigned >> 4))
                        .append("0123456789ABCDEF".charAt(unsigned & 15));
            }
        }
        require(canonical.toString().equals(value));
        return text;
    }

    /** RFC 3986 未保留字节只能直接出现。 */
    private static boolean unreserved(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }

    /** 非规范目标在领域处理之前失败关闭。 */
    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException();
        }
    }
}
