import java.io.FileInputStream;
import java.io.FileOutputStream;
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


/**
 * Encrypting JPEG images after compression.
 *
 * @author Emanuel Günther
 */
public class JpegEncryptionHandler {
    private static final byte JPEG_ZERO = (byte)0x00;
    private static final byte JPEG_MARKER = (byte)0xFF;
    private static final byte JPEG_SOI = (byte)0xD8;
    private static final byte JPEG_EOI = (byte)0xD9;
    private static final byte JPEG_DQT = (byte)0xDB;

    private byte[] encryptionKey = null;
    private byte[] encryptionSalt = null;
    private byte[] inImage = null;
    private byte[] outImage = null;
    private int position = 0;

    /**
     * Create a new JpegEncryptionHandler.
     *
     * @param key the key for encryption and decryption
     * @param salt salt for determining the input vector for encryption and decryption
     */
    public JpegEncryptionHandler(byte[] key, byte[] salt) {
        assert key.length == 16 : "Key has to be 16 Bytes.";
        assert salt.length == 14 : "Salt has to be 14 Bytes.";

        encryptionKey = key;
        encryptionSalt = salt;
    }

    /**
     * Decrypt an encrypted JPEG image.
     *
     * @param image the encrypted image
     * @return the decrypted image, or null if decryption failed
     */
    public byte[] decrypt(byte[] image) {
        position = 0;
        inImage = image;
        outImage = new byte[image.length];

        if (!seekToDqt()) {
            return null;
        }
        System.arraycopy(inImage, 0, outImage, 0, position);

        if (!cryptDqt(false)) {
            return null;
        }

        // copy remaining data
        copyData(inImage.length - position);

        return outImage;
    }

    /**
     * Encrypt an encrypted JPEG image.
     *
     * @param image the image
     * @return the encrypted image, or null if encryption failed
     */
    public byte[] encrypt(byte[] image) {
        position = 0;
        inImage = image;
        outImage = new byte[image.length];

        if (!seekToDqt()) {
            return null;
        }
        System.arraycopy(inImage, 0, outImage, 0, position);

        if (!cryptDqt(true)) {
            return null;
        }

        // copy remaining data
        copyData(inImage.length - position);

        return outImage;
    }

    /**
     * Copy data from input image to output image.
     *
     * The data beginning from "position" is copied in the length
     * of "length". Also the member variable "position" is increased
     * by the amount of copied bytes.
     *
     * @param length size of data to copy
     */
    private void copyData(int length) {
        System.arraycopy(inImage, position, outImage, position, length);
        position += length;
    }

    /**
     * Encrypt or decrypt the quantization tables.
     *
     * The position is required to be at the DQT marker.
     * Processed are just the quantization table elements, not the
     * precision nor the destination identifier.
     *
     * @param encryption Has to be true, if the tables should be encrypted; false indicates decryption.
     * @return true if cryptographic operation was successful, false otherwise
     */
    private boolean cryptDqt(boolean encryption) {
        copyData(2); // omit the marker
        int length = Byte.toUnsignedInt(inImage[position]) << 8
                | Byte.toUnsignedInt(inImage[position+1]);

        length -= 2; // remove length parameter from data length
        copyData(2); // length was extracted

        int dqtCount = length / 65; // one table has 64 entries and 1 byte precision/identifier

        boolean success = true;
        for (int i = 0; i < dqtCount; i++) {
            success |= cryptTable(encryption);
        }

        return success;
    }

    /**
     * Encrypt or decrypt a single quantization table.
     *
     * @param encryption Has to be true, if the tables should be encrypted; false indicates decryption.
     * @return true if cryptographic operation was successful, false otherwise
     */
    private boolean cryptTable(boolean encryption) {
        copyData(1); // discard precision and identifier

        // multiplication with 2^16 for counter
        byte[] ivData = new byte[encryptionSalt.length + 2];
        System.arraycopy(encryptionSalt, 0, ivData, 0, encryptionSalt.length);

        byte[] ciphertext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            Key key = new SecretKeySpec(encryptionKey, "AES");
            IvParameterSpec iv = new IvParameterSpec(ivData);

            if (encryption) {
                cipher.init(Cipher.ENCRYPT_MODE, key, iv);
            } else {
                cipher.init(Cipher.DECRYPT_MODE, key, iv);
            }

            byte[] dqtData = Arrays.copyOfRange(inImage, position, position + 64);
            ciphertext = cipher.doFinal(dqtData);
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

        if (ciphertext != null) {
            System.arraycopy(ciphertext, 0, outImage, position, ciphertext.length);
            position += ciphertext.length;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Seek to the next quantization table segment.
     *
     * @return true if skip successfull, false otherwise
     */
    private boolean seekToDqt() {
        boolean marker = false;
        boolean eoiFound = false;
        boolean dqtFound = false;

        while (!eoiFound && !dqtFound) {
            if (marker) {
                switch (inImage[position]) {
                case JPEG_MARKER:
                    break;
                case JPEG_DQT:
                    dqtFound = true;
                    position -= 2; // start at marker and revert increment
                    break;
                case JPEG_EOI:
                    eoiFound = true;
                    break;
                default:
                    marker = false;
                    break;
                }
            } else {
                switch (inImage[position]) {
                case JPEG_MARKER:
                    marker = true;
                    break;
                }
            }

            position++;
        }

        return dqtFound;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("To run the tests for this encryption, specify a JPEG file.");
            return;
        }

        FileInputStream fis = new FileInputStream(args[0]);
        byte[] plainImage = new byte[fis.available()];
        fis.read(plainImage);

        byte[] key = new byte[]{
            (byte)0xE1, (byte)0xF9, (byte)0x7A, (byte)0x0D, (byte)0x3E, (byte)0x01, (byte)0x8B, (byte)0xE0,
            (byte)0xD6, (byte)0x4F, (byte)0xA3, (byte)0x2C, (byte)0x06, (byte)0xDE, (byte)0x41, (byte)0x39};
        byte[] salt = new byte[]{
            (byte)0x0E, (byte)0xC6, (byte)0x75, (byte)0xAD, (byte)0x49, (byte)0x8A, (byte)0xFE,
            (byte)0xEB, (byte)0xB6,	(byte)0x96, (byte)0x0B, (byte)0x3A, (byte)0xAB, (byte)0xE6};
        JpegEncryptionHandler jeh = new JpegEncryptionHandler(key, salt);

        byte[] cipherImage = jeh.encrypt(plainImage);
        if (cipherImage != null) {
            FileOutputStream cipherFos = new FileOutputStream("encrypted.jpeg");
            cipherFos.write(cipherImage);
        } else {
            System.out.println("Error at encrypting the image.");
        }

        byte[] decryptedImage = jeh.decrypt(cipherImage);
        if (decryptedImage != null) {
            FileOutputStream decFos = new FileOutputStream("decrypted.jpeg");
            decFos.write(decryptedImage);
        } else {
            System.out.println("Error at decrypting the image.");
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

