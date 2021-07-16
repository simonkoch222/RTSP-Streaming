/**
 * Class for statistic values of the RTP packet reception.
 *
 * @author Emanuel Günther
 */
public class ReceptionStatistic {
    public int correctedPackets = 0;
    public int framesLost = 0;
    public int notCorrectedPackets = 0;
    public int packetsLost = 0;
    public int playbackIndex = -1;
    public int receivedPackets = 0;
    public int requestedFrames = 0;
    public int latestSequenceNumber = -1;
}

