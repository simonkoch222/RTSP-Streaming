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
        return false;
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
}

