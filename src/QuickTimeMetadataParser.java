import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parse metadata from an QuickTime file
 *
 * @author Emanuel Günther (s76954)
 */
public class QuickTimeMetadataParser {
    private static final String QT_BRAND_STRING = "qt  ";

    private static int timeScale = 0;
    private static int duration = 0;
    private static int smplCount = 0;
    private static int smplDuration = 0;

    /**
     * Parse the QuickTime file and return the metadata
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

        VideoMetadata meta = null;
        while (meta == null) {
            try {
                meta = parseAtom(fis);
            } catch (Exception ex) {
                logger.log(Level.SEVERE, ex.toString());
                return null;
            }
        }

        return meta;
    }

    private static boolean checkFileType(FileInputStream fis, int size) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        byte data[] = new byte[size];
        try {
            int nbytes = fis.read(data);
            if (nbytes == -1) {
                logger.log(Level.WARNING, "EOF reached");
                return false;
            }
        } catch (IOException ioex) {
            logger.log(Level.SEVERE, ioex.toString());
            return false;
        }

        String majorBrand = new String(data, 0, 4);
        String minorVersion = new String(data, 4, 4);
        boolean isQt = false;
        for (int i = 8; i < size; i += 4) {
            String compBrand = new String(data, i, 4);
            if (compBrand.equals(QT_BRAND_STRING)) {
                isQt = true;
            }
        }
        return isQt;
    }

    /**
     * Parse a single atom
     *
     * If an error occurs an Exception is thrown.
     *
     * @param fis File to be parsed
     * @return metadata if it was found, null otherwise
     * @throws Exception if an error occurs
     */
    private static VideoMetadata parseAtom(FileInputStream fis) throws Exception {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        byte header[] = new byte[8];
        int nbytes = fis.read(header);
        if (nbytes == -1) {
            throw new Exception("EOF reached");
        }

        int size = parseInteger(Arrays.copyOfRange(header, 0, 4));
        String type = new String(header, 4, 4);
        size -= 8; // subtract size and type

        // DEBUG:
        // logger.log(Level.FINE, type + ": " + size);
        switch (type) {
            case "ftyp":
                if (!checkFileType(fis, size)) {
                    throw new Exception("File is not an QuickTime Movie!");
                }
                break;
            case "mdhd":
                if (!parseMediaHeaderAtom(fis, size)) {
                    throw new Exception("Media Header Atom could not be parsed correctly");
                }
                break;
            case "stts":
                if (!parseTimeToSampleAtom(fis, size)) {
                    throw new Exception("Time-To-Sample Atom could not be parsed correctly");
                }
                break;
            // atoms to recognize, but not to skip
            case "moov":
            case "trak":
            case "mdia":
            case "minf":
            case "stbl":
                break;
            // atoms to skip
            case "dinf":
            case "edts":
            case "elst":
            case "hdlr":
            case "mdat":
            case "mvhd":
            case "stsd":
            case "tkhd":
            case "udta":
            case "vmhd":
            case "wide":
                fis.skip(size);
                break;
            default:
                logger.log(Level.INFO, "Atom type " + type + " not recognized");
                fis.skip(size);
                break;
        }

        // check if all necessary information available
        if (timeScale != 0 && duration != 0 && smplCount != 0 && smplDuration != 0) {
            double fps = (double)timeScale / (double)smplDuration;
            double dur = (double)duration / (double)timeScale;

            // reset all values for a possible next run
            timeScale = 0;
            duration = 0;
            smplCount = 0;
            smplDuration = 0;

            return new VideoMetadata((int)fps, dur);
        } else {
            return null;
        }
    }

    /**
     * Parse an 4-byte array to int
     *
     * @param data 4-byte array
     * @return Parsed value
     */
    private static int parseInteger(byte data[]) {
        assert data.length == 4 : "Only 4-byte arrays are supported";
        int val = ((data[0] & 0xFF) << 24) |
        ((data[1] & 0xFF) << 16) |
        ((data[2] & 0xFF) << 8) |
        ((data[3] & 0xFF) << 0);
        return val;
    }

    private static boolean parseMediaHeaderAtom(FileInputStream fis, int size) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        byte data[] = new byte[size];
        try {
            fis.read(data);
        } catch (IOException ioex) {
            logger.log(Level.SEVERE, ioex.toString());
            return false;
        }

        int version = data[0];
        byte flags[] = Arrays.copyOfRange(data, 1, 4);
        byte creationTime[] = Arrays.copyOfRange(data, 4, 8);
        byte modificationTime[] = Arrays.copyOfRange(data, 8, 12);
        byte bTimeScale[] = Arrays.copyOfRange(data, 12, 16);
        byte bDuration[] = Arrays.copyOfRange(data, 16, 20);
        byte language[] = Arrays.copyOfRange(data, 20, 22);
        byte quality[] = Arrays.copyOfRange(data, 22, 24);

        timeScale = parseInteger(bTimeScale);
        duration = parseInteger(bDuration);

        return true;
    }

    private static boolean parseTimeToSampleAtom(FileInputStream fis, int size) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        byte data[] = new byte[size];
        try {
            fis.read(data);
        } catch (IOException ioex) {
            logger.log(Level.SEVERE, ioex.toString());
            return false;
        }

        int version = data[0];
        byte flags[] = Arrays.copyOfRange(data, 1, 4);
        byte numberEntries[] = Arrays.copyOfRange(data, 4, 8);
        byte ttsTable[] = Arrays.copyOfRange(data, 8, size);

        int nbEntries = parseInteger(numberEntries);
        if (nbEntries != 1) {
            return false;
        } else {
            smplCount = parseInteger(Arrays.copyOfRange(ttsTable, 0, 4));
            smplDuration = parseInteger(Arrays.copyOfRange(ttsTable, 4, 8));
            return true;
        }
    }
}

