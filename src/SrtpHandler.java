import java.security.Key;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/*
 * The format of an SRTP packet:
 *
 *
 *       0                   1                   2                   3
 *     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+<+
 *    |V=2|P|X|  CC   |M|     PT      |       sequence number         | |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ |
 *    |                           timestamp                           | |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ |
 *    |           synchronization source (SSRC) identifier            | |
 *    +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+ |
 *    |            contributing source (CSRC) identifiers             | |
 *    |                               ....                            | |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ |
 *    |                   RTP extension (OPTIONAL)                    | |
 *  +>+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ |
 *  | |                          payload  ...                         | |
 *  | |                               +-------------------------------+ |
 *  | |                               | RTP padding   | RTP pad count | |
 *  +>+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+<+
 *  | ~                     SRTP MKI (OPTIONAL)                       ~ |
 *  | +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ |
 *  | :                 authentication tag (RECOMMENDED)              : |
 *  | +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ |
 *  |                                                                   |
 *  +- Encrypted Portion*                      Authenticated Portion ---+
 */

/**
 * A handler for creating and processing SRTP packets.
 *
 * This handler currently just supports the default transformations for
 * encryption and key derivation. Message Authentication is not supported yet.
 *
 * @author Emanuel Günther
 */
public class SrtpHandler {
    public enum EncryptionAlgorithm {
        NONE,
        AES_CTR
    }
    public enum MacAlgorithm {
        NONE
    }
    private enum Label {
        ENCRYPTION (0x00),
        MAC (0x01),
        SALTING (0x02);

        public byte value;
        Label(int val) {
            this.value = (byte)val;
        }
    }

    /* All values set for variables are in byte! */

    /* This variables are set at the initialization of this handler
     * and can be used safely.
     */
    private EncryptionAlgorithm cipherId = EncryptionAlgorithm.NONE;
    private int key_derivation_rate = 0;
    private MacAlgorithm macId = MacAlgorithm.NONE;
    private byte[] masterKey = null;
    private int masterKeyIdentifier = 0;
    private boolean masterKeyIndicator = false; // is MKI present in SRTP packets
    private int masterKeyLength = 16; // default of RFC 3711
    private long masterKeyPacketCounter = 0; // amount of packets encrypted with this key
    private byte[] masterSalt = null;
    private int masterSaltLength = 14; // default of RFC 3711
    private int n_b = 0; // bit size of the block for the cipher
    private int n_e = 16; // length of session key for encryption (k_e)
    private int n_s = 14; // length of session key for salting (k_s)
    private long roc = 0; // rollover counter, unsigned 32-bit
    private short s_l = -1; // receiver only, highest received RTP sequence number, 16-bit
    private byte[] ssrc = null;

    /* The following variables are updated during execution for their
     * specific need. Do not use them without prior definition!
     */
    private byte[] k_e = null; // session encryption key
    private byte[] k_s = null; // session salting key

    /**
     * Create a SrtpHandler with it's cryptographic context.
     *
     * @param cipherId The algorithm and mode to be used for encryption.
     * @param macId The algorithm used for message authentication.
     * @param masterKey The master key for key derivation.
     * @throws InvalidKeyException if the master key does not have the required size
     * @throws InvalidAlgorithmParameterException if the master salt does not have the required size
     */
    public SrtpHandler(EncryptionAlgorithm cipherId, MacAlgorithm macId, byte[] masterKey, byte[] masterSalt, int ssrc)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        this.cipherId = cipherId;
        this.macId = macId;
        if (masterKey != null && masterKey.length != masterKeyLength) {
            throw new InvalidKeyException("Master key has not the required size.");
        }
        this.masterKey = masterKey;
        if (masterSalt != null && masterSalt.length != masterSaltLength) {
            throw new InvalidAlgorithmParameterException("Master salt has not the required size.");
        }
        this.masterSalt = masterSalt;
        this.ssrc = SrtpHandler.intToByteArray(ssrc);

        computeInitialValues();
    }

    /**
     * Check if the cryptographic context of SRTP is initialized.
     *
     * @return true if all necessary values are set, false otherwise
     */
    public boolean isInitialized() {
        boolean initialized = true;

        initialized &= (masterKey != null);
        initialized &= (masterKeyPacketCounter < (1 << 48));
        initialized &= (masterSalt != null);
        initialized &= (k_e != null);
        initialized &= (k_s != null);

        return initialized;
    }

    private byte[] aesKeyDerivation(int keyLength, byte[] x) {
        assert x.length == 14 : "AES-Key-Derivation: x has not 112 Bit.";

        // multiplication with 2^16
        byte[] ivData = new byte[x.length + 2];
        System.arraycopy(x, 0, ivData, 0, x.length);

        byte[] ciphertext = null;
        int dataLength = (int)(Math.ceil((double)keyLength / (double)n_b) * 16);
        /* To obtain the key stream from the AES encryption operation
         * empty data has to be encrypted. The reason for that is that
         * in Counter Mode (CTR) a key stream is calculated and XORed
         * with the data. An XOR with empty data creates the key stream.
         */
        byte[] emptyData = new byte[dataLength];

        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            Key key = new SecretKeySpec(masterKey, "AES");
            IvParameterSpec iv = new IvParameterSpec(ivData);

            cipher.init(Cipher.ENCRYPT_MODE, key, iv);

            ciphertext = cipher.doFinal(emptyData);
        } catch (NoSuchAlgorithmException nsaex) {
            System.out.println(nsaex);
        } catch (NoSuchPaddingException nspex) {
            System.out.println(nspex);
        } catch (InvalidAlgorithmParameterException iapex) {
            System.out.println(iapex);
        } catch (InvalidKeyException ikex) {
            System.out.println(ikex);
        } catch (IllegalBlockSizeException ibsex) {
            System.out.println(ibsex);
        } catch (BadPaddingException bpex) {
            System.out.println(bpex);
        }

        if (ciphertext != null && ciphertext.length != keyLength) {
            byte[] tmp = new byte[keyLength];
            System.arraycopy(ciphertext, 0, tmp, 0, keyLength);
            ciphertext = tmp;
        }

        return ciphertext;
    }

    private void computeInitialValues() {
        switch (cipherId) {
        case AES_CTR:
            n_b = 16;
            break;
        case NONE:
            break;
        }

        if (masterKey != null && masterSalt != null) {
            k_e = computeSessionKey(0, Label.ENCRYPTION, n_e);
            k_s = computeSessionKey(0, Label.SALTING, n_s);
        }
    }

    private byte[] computeSessionKey(long index, Label label, int keyLength) {
        // All seemingly magic numbers in here are derived from RFC 3711.
        long r = key_derivation_rate == 0 ? 0 : index / key_derivation_rate;
        byte[] rData = Arrays.copyOfRange(longToByteArray(r), 2, 8);

        byte[] key_id = new byte[7];
        key_id[0] = (byte)label.value;
        System.arraycopy(rData, 0, key_id, 1, rData.length);

        byte[] x = new byte[14];
        int diff = x.length - key_id.length;
        for (int j = 0; j < x.length; j++) {
            if (j >= diff) {
                x[j] = (byte)(key_id[j-diff] ^ masterSalt[j]);
            } else {
                x[j] = masterSalt[j];
            }
        }

        byte[] key = aesKeyDerivation(keyLength, x);
        return key;
    }

    private byte[] aesCrypt(boolean encryption, long index, byte[] payload) {
        byte[] indexData = new byte[n_b];
        System.arraycopy(SrtpHandler.longToByteArray(index), 0, indexData, n_b - 10, 8);
        byte[] ivData = new byte[n_b];
        byte[] ssrcData = new byte[n_b];
        System.arraycopy(ssrc, 0, ssrcData, n_b - 4 - 8, 4);

        for (int i = 0; i < ivData.length - 2; i++) { // last 2 bytes left zero for counter
            ivData[i] = (byte)(k_s[i] ^ ssrcData[i] ^ indexData[i]);
        }

        // todo: RFC 3711, p. 22: ensure that each IV value is a nonce
        // -> ROC || SEQ and SSRC must be destinct form any key

        byte[] ciphertext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            Key key = new SecretKeySpec(k_e, "AES");
            IvParameterSpec iv = new IvParameterSpec(ivData);

            if (encryption) {
                cipher.init(Cipher.ENCRYPT_MODE, key, iv);
            } else {
                cipher.init(Cipher.DECRYPT_MODE, key, iv);
            }

            ciphertext = cipher.doFinal(payload);
        } catch (NoSuchAlgorithmException nsaex) {
            System.out.println(nsaex);
        } catch (NoSuchPaddingException nspex) {
            System.out.println(nspex);
        } catch (InvalidAlgorithmParameterException iapex) {
            System.out.println(iapex);
        } catch (InvalidKeyException ikex) {
            System.out.println(ikex);
        } catch (IllegalBlockSizeException ibsex) {
            System.out.println(ibsex);
        } catch (BadPaddingException bpex) {
            System.out.println(bpex);
        }

        return ciphertext;
    }

    private static byte[] intToByteArray(int val) {
        byte[] data = new byte[4];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte)((val & (0xFF << i*8)) >> i*8);
        }
        return data;
    }

    private static byte[] longToByteArray(long val) {
        byte[] data = new byte[8];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte)((val & (0xFF << i*8)) >> i*8);
        }
        return data;
    }

    /**
     * Test the key derivation function.
     *
     * The SrtpHandler can be initialized with null-Values, because
     * this method sets all necessary values.
     *
     * The test values are from Section B.3 of RFC 3711.
     *
     * @return Result of the test.
     */
    public boolean testKeyDerivation() {
        boolean testPassed = true;
        masterKey = new byte[]{
            (byte)0xE1, (byte)0xF9, (byte)0x7A, (byte)0x0D, (byte)0x3E, (byte)0x01, (byte)0x8B, (byte)0xE0,
            (byte)0xD6, (byte)0x4F, (byte)0xA3, (byte)0x2C, (byte)0x06, (byte)0xDE, (byte)0x41, (byte)0x39};
        masterSalt = new byte[]{
            (byte)0x0E, (byte)0xC6, (byte)0x75, (byte)0xAD, (byte)0x49, (byte)0x8A, (byte)0xFE,
            (byte)0xEB, (byte)0xB6,	(byte)0x96, (byte)0x0B, (byte)0x3A, (byte)0xAB, (byte)0xE6};
        computeInitialValues();

        byte[] r_encryption = new byte[]{ // reference value
            (byte)0xC6, (byte)0x1E, (byte)0x7A, (byte)0x93, (byte)0x74, (byte)0x4F, (byte)0x39, (byte)0xEE,
            (byte)0x10, (byte)0x73, (byte)0x4A, (byte)0xFE, (byte)0x3F, (byte)0xF7, (byte)0xA0, (byte)0x87};
        byte[] k_encryption = computeSessionKey(0, Label.ENCRYPTION, n_e);
        testPassed &= Arrays.equals(r_encryption, k_encryption);

        byte[] r_salting = new byte[]{ // reference value
            (byte)0x30, (byte)0xCB, (byte)0xBC, (byte)0x08, (byte)0x86, (byte)0x3D, (byte)0x8C,
            (byte)0x85, (byte)0xD4, (byte)0x9D, (byte)0xB3, (byte)0x4A, (byte)0x9A, (byte)0xE1};
        byte[] k_salting = computeSessionKey(0, Label.SALTING, n_s);
        testPassed &= Arrays.equals(r_salting, k_salting);

        byte[] r_auth = new byte[]{ // reference value
            (byte)0xCE, (byte)0xBE, (byte)0x32, (byte)0x1F, (byte)0x6F, (byte)0xF7, (byte)0x71, (byte)0x6B,
            (byte)0x6F, (byte)0xD4, (byte)0xAB, (byte)0x49, (byte)0xAF, (byte)0x25, (byte)0x6A, (byte)0x15,
            (byte)0x6D, (byte)0x38, (byte)0xBA, (byte)0xA4, (byte)0x8F, (byte)0x0A, (byte)0x0A, (byte)0xCF,
            (byte)0x3C, (byte)0x34, (byte)0xE2, (byte)0x35, (byte)0x9E, (byte)0x6C, (byte)0xDB, (byte)0xCE,
            (byte)0xE0, (byte)0x49, (byte)0x64, (byte)0x6C, (byte)0x43, (byte)0xD9, (byte)0x32, (byte)0x7A,
            (byte)0xD1, (byte)0x75, (byte)0x57, (byte)0x8E, (byte)0xF7, (byte)0x22, (byte)0x70, (byte)0x98,
            (byte)0x63, (byte)0x71, (byte)0xC1, (byte)0x0C, (byte)0x9A, (byte)0x36, (byte)0x9A, (byte)0xC2,
            (byte)0xF9, (byte)0x4A, (byte)0x8C, (byte)0x5F, (byte)0xBC, (byte)0xDD, (byte)0xDC, (byte)0x25,
            (byte)0x6D, (byte)0x6E, (byte)0x91, (byte)0x9A, (byte)0x48, (byte)0xB6, (byte)0x10, (byte)0xEF,
            (byte)0x17, (byte)0xC2, (byte)0x04, (byte)0x1E, (byte)0x47, (byte)0x40, (byte)0x35, (byte)0x76,
            (byte)0x6B, (byte)0x68, (byte)0x64, (byte)0x2C, (byte)0x59, (byte)0xBB, (byte)0xFC, (byte)0x2F,
            (byte)0x34, (byte)0xDB, (byte)0x60, (byte)0xDB, (byte)0xDF, (byte)0xB2};
        byte[] k_auth = computeSessionKey(0, Label.MAC, 94);
        testPassed &= Arrays.equals(r_auth, k_auth);

        return testPassed;
    }

    public static void main(String[] args) {
        try {
            SrtpHandler srtp = new SrtpHandler(SrtpHandler.EncryptionAlgorithm.AES_CTR,
                    SrtpHandler.MacAlgorithm.NONE, null, null, 0);
            boolean passedKeyDerivation = srtp.testKeyDerivation();
            System.out.println("Test (Key Derivation): " + (passedKeyDerivation ? "" : "not ") + "passed");
        } catch (InvalidKeyException ikex) {
            System.out.println(ikex);
        } catch (InvalidAlgorithmParameterException iapex) {
            System.out.println(iapex);
        }
    }

    public static String hexdump(byte[] data) {
        String b = "";
		for (int i = 0; i < data.length; i++) {
            b += String.format("%2s", Integer.toHexString(data[i] & 0xFF)).replace(' ', '0');
            if (data.length > 16 && (i + 1) % 16 == 0 && i != 0) {
                b += "\n";
            }
        }
        return b;
    }
}

