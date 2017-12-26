package at.favre.lib.armadillo;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.text.Normalizer;

import at.favre.lib.bytes.Bytes;
import at.favre.lib.crypto.HKDF;

/**
 * @author Patrick Favre-Bulle
 * @since 18.12.2017
 */

final class DefaultEncryptionProtocol implements EncryptionProtocol {
    private final byte PROTOCOL_VERSION = 0;

    private final byte[] preferenceSalt;
    private final EncryptionFingerprint fingerprint;
    private final KeyStretchingFunction keyStretchingFunction;
    private final SymmetricEncryption symmetricEncryption;
    private final DataObfuscator.Factory dataObfuscatorFactory;
    private final ContentKeyDigest contentKeyDigest;
    private final SecureRandom secureRandom;
    private final int keyLengthBit;

    private DefaultEncryptionProtocol(byte[] preferenceSalt, EncryptionFingerprint fingerprint,
                                      ContentKeyDigest contentKeyDigest, SymmetricEncryption symmetricEncryption,
                                      @SymmetricEncryption.KeyStrength int keyStrength, KeyStretchingFunction keyStretchingFunction,
                                      DataObfuscator.Factory dataObfuscatorFactory, SecureRandom secureRandom) {
        this.preferenceSalt = preferenceSalt;
        this.symmetricEncryption = symmetricEncryption;
        this.keyStretchingFunction = keyStretchingFunction;
        this.fingerprint = fingerprint;
        this.contentKeyDigest = contentKeyDigest;
        this.keyLengthBit = symmetricEncryption.byteSizeLength(keyStrength) * 8;
        this.dataObfuscatorFactory = dataObfuscatorFactory;
        this.secureRandom = secureRandom;
    }

    @Override
    public String deriveContentKey(String originalContentKey) {
        return contentKeyDigest.derive(Bytes.from(originalContentKey).append(preferenceSalt).encodeUtf8(), "contentKey");
    }

    @Override
    public byte[] encrypt(@NonNull String contentKey, byte[] rawContent) throws EncryptionProtocolException {
        return encrypt(contentKey, null, rawContent);
    }

    @Override
    public byte[] encrypt(@NonNull String contentKey, char[] password, byte[] rawContent) throws EncryptionProtocolException {
        byte[] fingerprintBytes = new byte[0];
        byte[] key = new byte[0];

        try {
            byte[] contentSalt = Bytes.random(16, secureRandom).array();

            fingerprintBytes = fingerprint.getBytes();
            key = keyDerivationFunction(contentKey, fingerprintBytes, contentSalt, preferenceSalt, password);
            byte[] encrypted = symmetricEncryption.encrypt(key, rawContent);

            DataObfuscator obfuscator = dataObfuscatorFactory.create(Bytes.from(contentKey).append(fingerprintBytes).array());
            obfuscator.obfuscate(encrypted);
            obfuscator.clearKey();

            return encode(contentSalt, encrypted);
        } catch (SymmetricEncryptionException e) {
            throw new EncryptionProtocolException(e);
        } finally {
            Bytes.wrap(fingerprintBytes).mutable().secureWipe();
            Bytes.wrap(key).mutable().secureWipe();
        }
    }

    private byte[] encode(byte[] contentSalt, byte[] encrypted) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + contentSalt.length + 4 + encrypted.length);
        buffer.put(PROTOCOL_VERSION);
        buffer.put((byte) contentSalt.length);
        buffer.put(contentSalt);
        buffer.putInt(encrypted.length);
        buffer.put(encrypted);
        return buffer.array();
    }

    @Override
    public byte[] decrypt(@NonNull String contentKey, byte[] encryptedContent) throws EncryptionProtocolException {
        return decrypt(contentKey, null, encryptedContent);
    }

    @Override
    public byte[] decrypt(@NonNull String contentKey, char[] password, byte[] encryptedContent) throws EncryptionProtocolException {
        byte[] fingerprintBytes = new byte[0];
        byte[] key = new byte[0];

        try {
            fingerprintBytes = fingerprint.getBytes();

            ByteBuffer buffer = ByteBuffer.wrap(encryptedContent);
            if (buffer.get() != PROTOCOL_VERSION) {
                throw new SecurityException("illegal protocol version");
            }

            byte[] contentSalt = new byte[buffer.get()];
            buffer.get(contentSalt);

            byte[] encrypted = new byte[buffer.getInt()];
            buffer.get(encrypted);

            DataObfuscator obfuscator = dataObfuscatorFactory.create(Bytes.from(contentKey).append(fingerprintBytes).array());
            obfuscator.deobfuscate(encrypted);
            obfuscator.clearKey();
            key = keyDerivationFunction(contentKey, fingerprintBytes, contentSalt, preferenceSalt, password);

            return symmetricEncryption.decrypt(key, encrypted);
        } catch (SymmetricEncryptionException e) {
            throw new EncryptionProtocolException(e);
        } finally {
            Bytes.wrap(fingerprintBytes).mutable().secureWipe();
            Bytes.wrap(key).mutable().secureWipe();
        }
    }

    private byte[] keyDerivationFunction(String contentKey, byte[] fingerprint, byte[] contentSalt, byte[] preferenceSalt, @Nullable char[] password) {
        Bytes ikm = Bytes.wrap(fingerprint).append(contentSalt).append(Bytes.from(contentKey, Normalizer.Form.NFKD));

        if (password != null) {
            ikm.append(keyStretchingFunction.stretch(contentSalt, password, 32));
        }

        return HKDF.fromHmacSha512().extractAndExpand(preferenceSalt, ikm.array(), "DefaultEncryptionProtocol".getBytes(), keyLengthBit / 8);
    }

    public static final class Factory implements EncryptionProtocol.Factory {

        private final EncryptionFingerprint fingerprint;
        private final ContentKeyDigest contentKeyDigest;
        private final SymmetricEncryption symmetricEncryption;
        @SymmetricEncryption.KeyStrength
        private final int keyStrength;
        private final KeyStretchingFunction keyStretchingFunction;
        private final DataObfuscator.Factory dataObfuscatorFactory;
        private final SecureRandom secureRandom;

        Factory(EncryptionFingerprint fingerprint, ContentKeyDigest contentKeyDigest,
                SymmetricEncryption symmetricEncryption, int keyStrength,
                KeyStretchingFunction keyStretchingFunction, DataObfuscator.Factory dataObfuscatorFactory,
                SecureRandom secureRandom) {
            this.fingerprint = fingerprint;
            this.contentKeyDigest = contentKeyDigest;
            this.symmetricEncryption = symmetricEncryption;
            this.keyStrength = keyStrength;
            this.keyStretchingFunction = keyStretchingFunction;
            this.dataObfuscatorFactory = dataObfuscatorFactory;
            this.secureRandom = secureRandom;
        }

        @Override
        public EncryptionProtocol create(byte[] preferenceSalt) {
            return new DefaultEncryptionProtocol(preferenceSalt, fingerprint, contentKeyDigest, symmetricEncryption, keyStrength, keyStretchingFunction, dataObfuscatorFactory, secureRandom);
        }

        @Override
        public ContentKeyDigest getContentKeyDigest() {
            return contentKeyDigest;
        }

        @Override
        public DataObfuscator createDataObfuscator() {
            return dataObfuscatorFactory.create(fingerprint.getBytes());
        }

        @Override
        public SecureRandom getSecureRandom() {
            return secureRandom;
        }
    }
}
