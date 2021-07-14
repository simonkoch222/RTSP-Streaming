import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for RTP packets.
 *
 * Processes all RTP packets and provides JPEG images for displaying
 *
 * @author Emanuel Günther
 */
public class RtpHandler {
    public static final int RTP_PAYLOAD_JPEG = 26;

    private FecHandler fecHandler = null;

    // server side
    private int currentSeqNb = 0; // sequence number of current frame
    private boolean fecEncodingEnabled = false; // server side

    /**
     * Create a new RtpHandler as server.
     *
     * @param fecGroupSize Group size for FEC packets. If the value is 0, FEC will be disabled.
     */
    public RtpHandler(int fecGroupSize) {
        if (fecGroupSize > 0) {
            fecEncodingEnabled = true;
            fecHandler = new FecHandler(fecGroupSize);
        }
    }

    /**
     * Retrieve the current FEC packet, if it is available.
     *
     * @return FEC packet as byte array, null if no such packet available
     */
    public byte[] createFecPacket() {
        if (!isFecPacketAvailable()) {
            return null;
        }

        byte[] fecPacket = fecHandler.getPacket();
        return fecPacket;
    }

    /**
     * Check for the availability of an FEC packet.
     *
     * @return bolean value if FEC packet available
     */
    public boolean isFecPacketAvailable() {
        if (fecEncodingEnabled) {
            return fecHandler.isReady();
        } else {
            return false;
        }
    }

    /**
     * Transform a JPEG image to an RTP packet.
     *
     * Takes care of all steps inbetween.
     *
     * @param jpegImage JPEG image as byte array
     * @return RTP packet as byte array
     */
    public byte[] jpegToRtpPacket(final byte[] jpegImage, int framerate) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        byte[] payload;
        JpegFrame frame = JpegFrame.getFromJpegBytes(jpegImage);
        payload = frame.getAsRfc2435Bytes();

        currentSeqNb++;

        // Build an RTPpacket object containing the image
        // time has to be in scale with 90000 Hz (RFC 2435, 3.)
        RTPpacket packet = new RTPpacket(
                RTP_PAYLOAD_JPEG, currentSeqNb,
                currentSeqNb * (90000 / framerate),
                payload, payload.length);

        if (fecEncodingEnabled) {
            fecHandler.setRtp(packet);
        }

        return packet.getpacket();
    }

    /**
     * Set a new group size for the FEC error handling.
     *
     * @param newGroupSize new group size
     */
    public void setFecGroupSize(int newGroupSize) {
        fecHandler.setFecGroupSize(newGroupSize);
    }
}

