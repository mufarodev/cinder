package com.discord.loader;

public interface HostInvoker {
  byte[] invokeOrig(byte[] frame);

  default void applyOps(byte[] ops, int length) {}
}
