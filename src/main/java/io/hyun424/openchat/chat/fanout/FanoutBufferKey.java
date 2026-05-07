package io.hyun424.openchat.chat.fanout;

record FanoutBufferKey(Long roomId, Integer partitionId) {
}
