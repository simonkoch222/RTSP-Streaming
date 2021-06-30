import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Parse metadata from an AVI file
 *
 * @author Emanuel Günther (s76954)
 */
public class AviMetadataParser {
    private static final String LIST_STRING = "LIST";

    /** Parse the AVI file and return the metadata
     *
     * @param filename The path of the file to parse
     * @return Parsed metadata of the file if successful, null otherwise
     */
    public static VideoMetadata parse(String filename) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(filename);
        } catch (Exception ex) {
            logger.log(Level.SEVERE, ex.toString());
            return null;
        }

        if (checkListHeader(fis, "RIFF", "AVI ") == -1) {
            logger.log(Level.WARNING, "File " + filename + " is not an AVI!");
            try {
                fis.close();
            } catch (Exception ex) {
            }
            return null;
        }

        VideoMetadata meta = parseAviHeader(fis);

        try {
            fis.close();
        } catch (Exception ex) {
        }
        return meta;
    }

    /** Check if the next 8 bytes of the file are a chunk of
     *  the specified type.
     *
     *  @param fis InputStream of the file
     *  @param type Type of the chunk
     *  @return size of the chunk if such a chunk was found, -1 otherwise
     */
    private static int checkChunkHeader(FileInputStream fis, String type) {
      Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        byte header[] = new byte[8];
        try {
            int nbytes = fis.read(header);
            if (nbytes == -1) {
                logger.log(Level.WARNING, "EOF reached");
                return -1;
            }
        } catch (IOException ioex) {
            logger.log(Level.SEVERE, ioex.toString());
            return -1;
        }

        String tp = new String(header, 0, 4);
        if (!tp.equals(type)) {
            return -1;
        }

        int size = parseInteger(Arrays.copyOfRange(header, 4, 8));
        return size;
    }

    /** Check if the next 12 bytes of the file are a list of
     *  the specified type.
     *
     *  @param fis InputStream of the file
     *  @param label Label of the list
     *  @param type Type of the list
     *  @return size of the list if a list with the specified label and type was found, -1 otherwise
     */
    private static int checkListHeader(FileInputStream fis, String label, String type) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        int headerSize = 12;
        byte header[] = new byte[headerSize];
        try {
            int nbytes = fis.read(header);
            if (nbytes == -1) {
                logger.log(Level.WARNING, "EOF reached");
                return -1;
            }
        } catch (IOException ioex) {
            logger.log(Level.SEVERE, ioex.toString());
            return -1;
        }
        String lb = new String(header, 0, 4);
        if (!lb.equals(label)) {
            return -1;
        }

        int size = parseInteger(Arrays.copyOfRange(header, 4, 8));

        String tp = new String(header, 8, 4);
        if (!tp.equals(type)) {
            return -1;
        }

        return size;
    }

    private static VideoMetadata parseAviHeader(FileInputStream fis) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        if (checkListHeader(fis, LIST_STRING, "hdrl") == -1) {
            logger.log(Level.WARNING, "No header found");
            return null;
        }

        // The main AVI header does not contain useful information.
        if (!skipChunk(fis, "avih")) {
            logger.log(Level.WARNING, "Skipping main AVI header chunk was not successful");
            return null;
        }

        if (checkListHeader(fis, LIST_STRING, "strl") == -1) {
            logger.log(Level.WARNING, "Stream list not found");
            return null;
        }

        VideoMetadata meta = parseAviStreamHeaderChunk(fis);

        return meta;
    }

    private static VideoMetadata parseAviStreamHeaderChunk(FileInputStream fis) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        int size = checkChunkHeader(fis, "strh");
        if (size == -1) {
            return null;
        }

        byte data[] = new byte[size];
        try {
            int nbytes = fis.read(data);
            if (nbytes == -1) {
                logger.log(Level.WARNING, "EOF reached");
                return null;
            }
        } catch (IOException ioex) {
            logger.log(Level.SEVERE, ioex.toString());
            return null;
        }

        String fccType = new String(data, 0, 4);
        String fccHandler = new String(data, 4, 8);
        byte dwFlags[] = Arrays.copyOfRange(data, 8, 12);
        byte wPriority[] = Arrays.copyOfRange(data, 12, 14);
        byte wLanguage[] = Arrays.copyOfRange(data, 14, 16);
        byte dwInitialFrames[] = Arrays.copyOfRange(data, 16, 20);
        byte dwScale[] = Arrays.copyOfRange(data, 20, 24);
        byte dwRate[] = Arrays.copyOfRange(data, 24, 28);
        byte dwStart[] = Arrays.copyOfRange(data, 28, 32);
        byte dwLength[] = Arrays.copyOfRange(data, 32, 36);
        byte dwSuggestedBufferSize[] = Arrays.copyOfRange(data, 36, 40);
        byte dwQuality[] = Arrays.copyOfRange(data, 40, 44);
        byte dwSampleSize[] = Arrays.copyOfRange(data, 44, 48);
        // RECT: long (32 bit) left, top, right, bottom
        byte rcFrame[] = Arrays.copyOfRange(data, 48, 64);

        int scale = parseInteger(dwScale);
        int rate = parseInteger(dwRate);
        int length = parseInteger(dwLength);
        double fps = (double)rate / (double)scale;
        double duration = length / fps;

        return new VideoMetadata((int)fps, duration);
    }

    /** Parse an 4-byte array in little endian to int
     *
     * @param data 4-byte array
     * @return Parsed value
     */
    private static int parseInteger(byte[] data) {
        assert data.length == 4 : "Only 4-byte arrays are supported";
        int val = ((data[0] & 0xFF) << 0) |
            ((data[1] & 0xFF) << 8) |
            ((data[2] & 0xFF) << 16) |
            ((data[3] & 0xFF) << 24);
        return val;
    }

    private static boolean skipChunk(FileInputStream fis, String type) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        byte header[] = new byte[8];
        try {
            int nbytes = fis.read(header);
            if (nbytes == -1) {
                logger.log(Level.WARNING, "EOF reached");
                return false;
            }
        } catch (IOException ioex) {
            logger.log(Level.SEVERE, ioex.toString());
            return false;
        }

        String tp = new String(header, 0, 4);
        if (!tp.equals(type)) {
            return false;
        }

        int size = parseInteger(Arrays.copyOfRange(header, 4, 8));

        /* AVI paddes to the next WORD boundary. A WORD has 2 bytes.
         * Therefore if the size of the chunk (data without padding)
         * does not conform with a WORD boundary, an additional byte
         * has to be skipped.
         */
        if (size % 2 == 1) {
            size++;
        }

        try {
            long skipped = fis.skip(size);
        } catch (IOException ioex) {
            logger.log(Level.SEVERE, ioex.toString());
            return false;
        }

        return true;
    }
}

