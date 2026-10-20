// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.zip.CRC32;

import javax.security.auth.x500.X500Principal;

/**
 * Minimal APK writer: stored (uncompressed) zip entries aligned to 4 bytes, signed with
 * APK Signature Scheme v2 using an RSA key kept in the Android Keystore (the same key on every
 * export, so the pack installs over itself). Entries go straight to the file; only the central
 * directory is kept in memory.
 */
final class ApkWriter implements Closeable {
    private static final String KEY_ALIAS = "fi_pack";
    private static final int SIG_RSA_PKCS1_SHA256 = 0x0103;
    private static final int V2_BLOCK_ID = 0x7109871a;
    private static final int CHUNK = 1024 * 1024;

    private final File mFile;
    private OutputStream mOut;
    private int mOffset;
    private final ByteArrayOutputStream mCd = new ByteArrayOutputStream();
    private int mCount;
    private boolean mFinished;

    ApkWriter(File out) throws IOException {
        mFile = out;
        mOut = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
    }

    void add(String name, byte[] data) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        int offset = mOffset;
        // data must start on a 4-byte boundary (resources.arsc is required to be aligned)
        int pad = (4 - (offset + 30 + n.length) % 4) % 4;
        ByteBuffer h = le(30 + n.length + pad);
        h.putInt(0x04034b50).putShort((short) 10).putShort((short) 0x0800).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0x21) // 1980-01-01
                .putInt((int) crc.getValue()).putInt(data.length).putInt(data.length)
                .putShort((short) n.length).putShort((short) pad).put(n);
        for (int i = 0; i < pad; i++) h.put((byte) 0);
        mOut.write(h.array(), 0, h.capacity());
        mOut.write(data, 0, data.length);
        mOffset += h.capacity() + data.length;
        ByteBuffer c = le(46 + n.length);
        c.putInt(0x02014b50).putShort((short) 20).putShort((short) 10).putShort((short) 0x0800)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0x21)
                .putInt((int) crc.getValue()).putInt(data.length).putInt(data.length)
                .putShort((short) n.length).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset).put(n);
        mCd.write(c.array(), 0, c.capacity());
        mCount++;
    }

    /** Sign and complete the file: signing block, central directory, end record. */
    void finish() throws Exception {
        mOut.close();
        mOut = null;
        int entries = mOffset;
        byte[] cd = mCd.toByteArray();
        byte[] eocd = eocd(entries, cd.length);

        // v2: digest over entries, central directory and EOCD (its CD offset = where the
        // signing block will start, which is the end of the entries both before and after)
        byte[] digest = contentDigest(entries, cd, eocd);

        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ensureKey(ks);
        X509Certificate cert = (X509Certificate) ks.getCertificate(KEY_ALIAS);
        PrivateKey key = (PrivateKey) ks.getKey(KEY_ALIAS, null);

        byte[] digests = lp(lp(concat(u32(SIG_RSA_PKCS1_SHA256), lp(digest))));
        byte[] certs = lp(lp(cert.getEncoded()));
        byte[] signedData = concat(digests, certs, u32(0));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(signedData);
        byte[] signatures = lp(lp(concat(u32(SIG_RSA_PKCS1_SHA256), lp(s.sign()))));
        byte[] signer = concat(lp(signedData), signatures, lp(cert.getPublicKey().getEncoded()));
        byte[] v2 = lp(lp(signer));

        // APK Signing Block
        long pairLen = 4 + v2.length;
        long blockSize = 8 + pairLen + 8 + 16;
        ByteBuffer b = le((int) (8 + blockSize));
        b.putLong(blockSize).putLong(pairLen).putInt(V2_BLOCK_ID).put(v2).putLong(blockSize)
                .put("APK Sig Block 42".getBytes(StandardCharsets.US_ASCII));
        byte[] block = b.array();

        try (OutputStream out = new FileOutputStream(mFile, true)) {
            out.write(block);
            out.write(cd);
            out.write(eocd(entries + block.length, cd.length));
        }
        mFinished = true;
    }

    /** Closes the file; without a successful finish() the half-written APK is deleted. */
    @Override
    public void close() throws IOException {
        if (mOut != null) {
            mOut.close();
            mOut = null;
        }
        if (!mFinished) mFile.delete();
    }

    private byte[] eocd(int cdOffset, int cdSize) {
        ByteBuffer e = le(22);
        e.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) mCount)
                .putShort((short) mCount).putInt(cdSize).putInt(cdOffset).putShort((short) 0);
        return e.array();
    }

    /** 1 MB chunk digests of the entries (read back from the file), the CD and the EOCD. */
    private byte[] contentDigest(int entries, byte[] cd, byte[] eocd) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        int count = 0;
        byte[] buf = new byte[CHUNK];
        try (InputStream in = new FileInputStream(mFile)) {
            for (int off = 0; off < entries; off += CHUNK) {
                int len = Math.min(CHUNK, entries - off);
                for (int got = 0; got < len; ) {
                    int n = in.read(buf, got, len - got);
                    if (n < 0) throw new IOException("short read of " + mFile);
                    got += n;
                }
                chunks.write(chunkDigest(md, buf, 0, len));
                count++;
            }
        }
        for (byte[] sec : new byte[][]{cd, eocd}) {
            for (int off = 0; off < sec.length; off += CHUNK) {
                chunks.write(chunkDigest(md, sec, off, Math.min(CHUNK, sec.length - off)));
                count++;
            }
        }
        md.reset();
        md.update((byte) 0x5a);
        md.update(u32(count));
        md.update(chunks.toByteArray());
        return md.digest();
    }

    private static byte[] chunkDigest(MessageDigest md, byte[] data, int off, int len) {
        md.reset();
        md.update((byte) 0xa5);
        md.update(u32(len));
        md.update(data, off, len);
        return md.digest();
    }

    private static void ensureKey(KeyStore ks) throws Exception {
        if (ks.containsAlias(KEY_ALIAS)) return;
        Calendar from = Calendar.getInstance(), to = Calendar.getInstance();
        to.add(Calendar.YEAR, 100);
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        g.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=Flyme Icons pack, O=ncrc"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(from.getTime())
                .setCertificateNotAfter(to.getTime())
                .build());
        g.generateKeyPair();
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] u32(int v) {
        return le(4).putInt(v).array();
    }

    /** uint32 length-prefixed. */
    private static byte[] lp(byte[] data) {
        return concat(u32(data.length), data);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
