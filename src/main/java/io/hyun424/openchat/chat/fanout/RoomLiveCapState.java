package io.hyun424.openchat.chat.fanout;

class RoomLiveCapState {
    private long currentSecond = -1;
    private int emittedInCurrentSecond = 0;
    private int omittedSinceLastVisible = 0;

    synchronized LivePermit reserve(int maxMessagesPerSecond, int requestedMessages, long nowMillis) {
        long second = nowMillis / 1000;
        if (second != currentSecond) {
            currentSecond = second;
            emittedInCurrentSecond = 0;
        }

        int remaining = Math.max(0, maxMessagesPerSecond - emittedInCurrentSecond);
        int visible = Math.min(requestedMessages, remaining);
        emittedInCurrentSecond += visible;

        int omittedNow = requestedMessages - visible;
        if (visible == 0) {
            omittedSinceLastVisible += omittedNow;
            return new LivePermit(0, omittedNow);
        }

        int omittedForEnvelope = omittedSinceLastVisible + omittedNow;
        omittedSinceLastVisible = 0;
        return new LivePermit(visible, omittedForEnvelope);
    }
}
