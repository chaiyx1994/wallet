package org.bitcoinj.core;

public class Base58 {

    public static String encode(byte[] input) {
        return org.bitcoinj.base.Base58.encode(input);
    }

    public static byte[] decode(String input) {
        return org.bitcoinj.base.Base58.decode(input);
    }
}
