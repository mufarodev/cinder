package com.discord.loader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Frame {
  public static final int HEADER_SIZE = 16;
  public static final int KIND_REQUEST = 1;
  public static final int KIND_RESPONSE = 2;
  public static final int KIND_EVENT = 3;
  public static final int KIND_BATCH = 4;

  public static final byte[] DROP = new byte[0];

  private final byte[] raw;
  private final int version, moduleId, methodId, callId, payloadLength;

  private Frame(
      byte[] raw, int version, int moduleId, int methodId, int callId, int payloadLength) {
    this.raw = raw;
    this.version = version;
    this.moduleId = moduleId;
    this.methodId = methodId;
    this.callId = callId;
    this.payloadLength = payloadLength;
  }

  public static Frame parse(byte[] b) {
    if (b == null || b.length < HEADER_SIZE) return null;
    int kind = b[0] & 0xff;
    if (kind < 1 || kind > 4) return null;
    ByteBuffer q = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    int moduleId = q.getShort(2) & 0xffff;
    int methodId = q.getShort(4) & 0xffff;
    int callId = q.getInt(8);
    int len = q.getInt(12);
    if (len < 0 || len > b.length - HEADER_SIZE) return null;
    return new Frame(b, kind, moduleId, methodId, callId, len);
  }

  public static byte[] build(int kind, int moduleId, int methodId, int callId, byte[] payload) {
    if (kind < 1 || kind > 4) {
      throw new IllegalArgumentException("invalid frame kind: " + kind);
    }
    int len = payload != null ? payload.length : 0;
    byte[] out = new byte[HEADER_SIZE + len];
    ByteBuffer q = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
    q.put(0, (byte) kind);
    q.put(1, (byte) 0);
    q.putShort(2, (short) moduleId);
    q.putShort(4, (short) methodId);
    q.putShort(6, (short) 0);
    q.putInt(8, callId);
    q.putInt(12, len);
    if (len > 0) {
      System.arraycopy(payload, 0, out, HEADER_SIZE, len);
    }
    return out;
  }

  public byte[] raw() {
    return raw;
  }

  public int version() {
    return version;
  }

  public int kind() {
    return version;
  }

  public boolean isRequest() {
    return version == KIND_REQUEST;
  }

  public boolean isResponse() {
    return version == KIND_RESPONSE;
  }

  public boolean isEvent() {
    return version == KIND_EVENT;
  }

  public boolean isBatch() {
    return version == KIND_BATCH;
  }

  public int moduleId() {
    return moduleId;
  }

  public int methodId() {
    return methodId;
  }

  public int callId() {
    return callId;
  }

  public int payloadLength() {
    return payloadLength;
  }

  public int trailingLength() {
    return raw.length - HEADER_SIZE - payloadLength;
  }

  public byte[] payload() {
    byte[] out = new byte[payloadLength];
    System.arraycopy(raw, HEADER_SIZE, out, 0, payloadLength);
    return out;
  }

  public byte[] withPayload(byte[] newPayload) {
    int newLen = newPayload != null ? newPayload.length : 0;
    int trailing = trailingLength();
    byte[] out = new byte[HEADER_SIZE + newLen + trailing];

    System.arraycopy(raw, 0, out, 0, HEADER_SIZE);
    ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(12, newLen);
    if (newLen > 0) {
      System.arraycopy(newPayload, 0, out, HEADER_SIZE, newLen);
    }
    if (trailing > 0) {
      System.arraycopy(raw, HEADER_SIZE + payloadLength, out, HEADER_SIZE + newLen, trailing);
    }
    return out;
  }

  public byte[] reply(byte[] payload) {
    return build(KIND_RESPONSE, moduleId, methodId, callId, payload);
  }
}
