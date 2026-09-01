package org.bitcoinj.core;

public class Sha256Hash {

    public static byte[] hash(byte[] input) {
        return org.bitcoinj.base.Sha256Hash.hash(input);
    }
}
