package io.hyun424.openchat.infra.time;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class BucketKeyUtil {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm")
                    .withZone(ZoneOffset.UTC);

    public static String currentBucketKey() {
        return "hotchat:bucket:" + FORMATTER.format(Instant.now());
    }

    public static String bucketKeyMinutesAgo(long minutesAgo) {
        return "hotchat:bucket:" +
                FORMATTER.format(Instant.now().minusSeconds(minutesAgo * 60));
    }
}
