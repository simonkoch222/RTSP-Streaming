/*
    Information according to RFC 5109
    Implementation: http://apidocs.jitsi.org/libjitsi/

    FEC Packet Structure

  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                RTP Header (12 octets or more)                 |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                    FEC Header (10 octets)                     |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                      FEC Level 0 Header                       |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                     FEC Level 0 Payload                       |
   |                                                               |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                      FEC Level 1 Header                       |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                     FEC Level 1 Payload                       |
   |                                                               |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                            Cont.                              |
   |                                                               |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

    FEC Header

    0                   1                   2                   3
    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |E|L|P|X|  CC   |M| PT recovery |            SN base            |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                          TS recovery                          |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |        length recovery        |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+


    ULP Level Header

    0                   1                   2                   3
    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |       Protection Length       |             mask              |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |              mask cont. (present only when L = 1)             |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
*/

/**
 * @author Jörg Vogt
 * @version 1.0
 */

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FECpacket extends RTPpacket {
  private static final int FEC_HEADER_SIZE = 10;
  private static final int ULP_HEADER_SIZE0 = 4;
  private static final int ULP_HEADER_SIZE1 = 8;
  int headerAll;
  int payloadFec_size;

  // *** FEC-Header *************************
  byte[] fecHeader = new byte[FEC_HEADER_SIZE];
  int E = 0;
  int L;
  // XOR fields set to initial zero
  int P = 0;
  int X = 0;
  int CC = 0;
  int M = 0;
  int ptRecovery = 0;
  int snBase;
  int tsRecovery = 0;
  int lengthRecovery = 0;

  // *** ULP Level Header ********************
  byte[] ulpLevelHeader;
  int protectionLength;
  long mask;

  // *** FEC Parameters **********************
  int fecGroupSize; // FEC-Gruppengröße


  // ##############################################################################################

  /**
   * Construct FEC packet from parameters
   * @param PType Payload Type
   * @param Framenb Sequence Nr
   * @param Time   Time Stamp
   * @param maxGroupSize maximum supported group size
   * @param snBase base for sequence nr.
   */
  public FECpacket(int PType, int Framenb, int Time, int maxGroupSize, int snBase) {
    super(PType, Framenb, Time, new byte[0], 0);
    setFecHeader(maxGroupSize, snBase);
    setUlpLevelHeader(0,0,maxGroupSize);
  }

  // ##############################################################################################

  /**
   * Constructor for Receiver
   *
   * @param packet bitstream
   * @param packet_size size
   */
  public FECpacket(byte[] packet, int packet_size) {
    // packet includes FEC header
    // builds the RTP header
    super(packet, packet_size);

    extractFecHeader(); // sets the header arrays and the variables
    // removes the fec-header from the payload
    byte[] buf = new byte[payload.length- FEC_HEADER_SIZE - ulpLevelHeader.length];
    System.arraycopy(payload,FEC_HEADER_SIZE+ulpLevelHeader.length,
        buf, 0, payload.length-FEC_HEADER_SIZE-ulpLevelHeader.length);
    payload = buf;
  }

  // ##############################################################################################


  /**
   * Sets the FEC-Header-variables and generates the Header-Array
   *
   * @param maxGroupSize to generate al short or long mask for ULP
   * @param ptRec  PayloadType
   * @param snBase SN
   * @param tsRec  Timestamp
   * @param lengthRec Length
   */
  public void setFecHeader(int maxGroupSize, int ptRec, int snBase, int tsRec, int lengthRec) {
    ptRecovery = ptRec;
    tsRecovery = tsRec;
    lengthRecovery = lengthRec;
    setFecHeader(maxGroupSize, snBase);
  }

  private void setFecHeader(int maxGroupSize, int snBase) {
    this.snBase = snBase;
    L = maxGroupSize > 16 ? 1 : 0;
    setFecHeader();
  }

  private void setFecHeader() {
    // FEC-Header
    // P,X,CC,M,PT, TS  is  XORed
    fecHeader[0] = (byte) (E << 7 | L << 6);
    fecHeader[1] = (byte) (M<< 7 | ptRecovery);
    fecHeader[2] = (byte) (snBase >> 8);
    fecHeader[3] = (byte) (0xFF & snBase);
    fecHeader[4] = (byte) (tsRecovery >> 24);
    fecHeader[5] = (byte) (tsRecovery >> 16);
    fecHeader[6] = (byte) (tsRecovery >> 8);
    fecHeader[7] = (byte) (tsRecovery);
    fecHeader[8] = (byte) (lengthRecovery >> 8);
    fecHeader[9] = (byte) (0xFF & lengthRecovery);
  }


  /**
   * Sets the ULP-Variables and generates the Header
   *
   * @param level always 0
   * @param protectionLength set to max Length
   * @param fecGroupSize corresponding packets
   */
  public void setUlpLevelHeader(int level, int protectionLength, int fecGroupSize) {
    // Level is always 0
    this.protectionLength = protectionLength;
    this.fecGroupSize = fecGroupSize;

    // generate mask, MSB corresponds to i=0
    mask = 0x8000000000000000L;
    for (int i = 1; i < fecGroupSize; i++) {
      mask = 0x8000000000000000L | (mask >> 1);
    }
    if (L == 0) {
      ulpLevelHeader = new byte[ULP_HEADER_SIZE0];
    } else {
      ulpLevelHeader = new byte[ULP_HEADER_SIZE1];
    }
    // FEC-Level-Header
    ulpLevelHeader[0] = (byte) (protectionLength >> 8);
    ulpLevelHeader[1] = (byte) (0xFF & protectionLength);
    ulpLevelHeader[2] = (byte) (mask >> 56);
    ulpLevelHeader[3] = (byte) (mask >> 48);
    if (L == 1) {
      ulpLevelHeader[4] = (byte) (mask >> 40);
      ulpLevelHeader[5] = (byte) (mask >> 32);
      ulpLevelHeader[6] = (byte) (mask >> 24);
      ulpLevelHeader[7] = (byte) (mask >> 16);
    }
    headerAll = HEADER_SIZE + FEC_HEADER_SIZE + ulpLevelHeader.length;
    payloadFec_size = payload_size - FEC_HEADER_SIZE - ulpLevelHeader.length;
  }

  /**
   * Generates a list of involved RTP packets, starting with snBase
   * @return list of sequence numbers
   */
  public ArrayList<Integer> getRtpList() {
    // determine the involved media packets (base address + mask)
    ArrayList<Integer> list = new ArrayList<>();
    //System.out.println("FEC: base + mask " + snBase + " " + Long.toHexString(mask) );
    // generates involved packet numbers from mask
    for (int i = 0; i < 48; i++) {
      if ( (mask & 0x8000000000000000L) != 0 ) {
        list.add(snBase + i);
      }
      mask = mask << 1;
    }
    return list;
  }

  /**
   * Gets the whole FEC packet including RTP header as array
   *
   * @return the bitstream
   */
  @Override
  public byte[] getpacket() {
    Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    logger.log(Level.FINER, "FEC packet: " + payload_size + " " + payload.length);
    byte[] packet = new byte[payload_size + headerAll];
    setRtpHeader(); // set RTP Header again because of changing time stamp
    // RTP Header from array
    System.arraycopy(header, 0, packet, 0, HEADER_SIZE);
    // FEC Header from array
    System.arraycopy(fecHeader, 0, packet, HEADER_SIZE, FEC_HEADER_SIZE);
    // ULP Header from array
    System.arraycopy(
        ulpLevelHeader, 0, packet, HEADER_SIZE + FEC_HEADER_SIZE, ulpLevelHeader.length);
    // Payload starts from 0
    System.arraycopy(
        payload, 0, packet, headerAll, payload_size);
    return packet;
  }


  // ##############################################################################################


  // Java Unsigned Bytes
  // https://sites.google.com/site/gencoreoperative/index/java-development/java-unsigned-bytes

  /** retrieves the FEC-header from the RTP payload
   *  data is received from RTP packet payload
   *
   */
  private void extractFecHeader() {
    // byte[] payload = fec.getpayload();
    // copy the FEC Header als part of the RTP payload
    System.arraycopy(payload, 0, fecHeader, 0, FEC_HEADER_SIZE);
    L = (fecHeader[0] & 0b01000000) >> 6;
    // ignore P,X,CC,M yet
    ptRecovery = 0x7F & fecHeader[1];
    snBase = (0xFF & fecHeader[2]) * 256 + (0xFF & fecHeader[3]);
    // TODO check if correct
    tsRecovery =
        (0xFFFFFF & fecHeader[4]) * 0xFFFF
            + (0xFF & fecHeader[5])
            + (0xFF & fecHeader[6]) * 256
            + (0xFF & fecHeader[7]);
    lengthRecovery = (0xFF & fecHeader[8]) * 256 + (0xFF & fecHeader[9]);

    // ULP Level Header
    if (L == 0) ulpLevelHeader = new byte[ULP_HEADER_SIZE0];
    else ulpLevelHeader = new byte[ULP_HEADER_SIZE1];
    // copy the ULP header
    System.arraycopy(payload, FEC_HEADER_SIZE, ulpLevelHeader, 0, ulpLevelHeader.length);
    protectionLength = (0xFF & ulpLevelHeader[0]) * 256 + (0xFF & ulpLevelHeader[1]);
    // Small mask
    mask = ((0xFFL & ulpLevelHeader[2]) << 56) + ((0xFFL & ulpLevelHeader[3]) << 48);
    // System.out.println("FEC mask: " + ulpLevelHeader[2] + " " + ulpLevelHeader[3] + " " +
    // Long.toHexString(mask));
    // Large mask
    if (L == 1) {
      mask |=
          ((0xFFL & ulpLevelHeader[4]) << 40)
              + ((0xFFL & ulpLevelHeader[5]) << 32)
              + ((0xFFL & ulpLevelHeader[6]) << 24)
              + ((0xFFL & ulpLevelHeader[7]) << 16);
    }
  }

  // ###############################################################################################

  /**
   * Adds a RTP packet to the header and payload
   * Length adjusts to the longest packet
   * For sender and receiver
   *
   * @param rtp RTP packet
   */
  public void addRtp(RTPpacket rtp) {
    // sets the payload
    byte[] data = rtp.getpayload();

    // XOR actual data size
    lengthRecovery ^= data.length;

    // init of buffer and length recovery
    if (payload.length == 0) {
      payload = data;
    } else {
      // switch array so that payload is longest of all packets
      if (data.length > payload.length) {
        byte[] buf = data;
        data = payload;
        payload = buf;
      }
      for (int i = 0; i < data.length; i++) {
        payload[i] ^= data[i];
      }
    }
    payload_size = payload.length;

    // Header XOR -> P, X, CC, M, PT, TS
    P ^= rtp.Padding;
    X ^= rtp.Extension;
    CC ^= rtp.CC;
    M ^= rtp.Marker;
    ptRecovery ^= rtp.getpayloadtype();
    tsRecovery ^= rtp.gettimestamp();

    setFecHeader();  // update Header with changed variables
  }


  /**
   * Generates the lost RTP packet from the XORed values
   * @return rtp
   */
  public RTPpacket getLostRtp(int snr) {
    // TODO get the correct SNr
    return new RTPpacket(ptRecovery, snr  ,tsRecovery, payload, lengthRecovery);
  }


  // *************** Debugging *******************************************************************

  /**
   * Prints the FEC- and ULP-Header fields
   */
  public void printHeaders() {
    Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    logger.log(Level.FINER, "FEC-Header");
    printheader(FEC_HEADER_SIZE, fecHeader);
    logger.log(Level.FINER, "FEC-Level-Header");
    printheader(ulpLevelHeader.length, ulpLevelHeader);
    logger.log(Level.FINER, "FEC-Payload");
    printheader(3, payload);
  }
}